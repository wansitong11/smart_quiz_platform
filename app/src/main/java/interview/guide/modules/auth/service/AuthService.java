package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.dto.CurrentUserResponse;
import interview.guide.modules.auth.dto.LoginResponse;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.AuthUserPrincipal;
import interview.guide.modules.auth.security.CurrentUserHolder;
import interview.guide.modules.auth.security.JwtTokenService;
import interview.guide.modules.auth.security.TokenRevocationService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录、续期、退出、当前用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final RefreshTokenService refreshTokenService;
  private final TokenRevocationService tokenRevocationService;
  private final JwtTokenService jwtTokenService;
  private final UserAuthorityResolver authorityResolver;
  private final PasswordEncoder passwordEncoder;
  private final AuthProperties authProperties;
  private final UserRegistrationService userRegistrationService;

  /** 登录结果：响应体内容 + 需要写入 Cookie 的刷新令牌 */
  public record LoginResult(LoginResponse response, String refreshToken, long refreshTtlSeconds) {
  }

  /**
   * 自助注册。注册成功后不直接发令牌，用户回到登录界面重新登录。
   *
   * @return 标准化后的用户名
   */
  @Transactional
  public String register(String username, String rawPassword) {
    if (!authProperties.isRegistrationEnabled()) {
      throw new BusinessException(ErrorCode.AUTH_REGISTRATION_DISABLED, "当前未开放注册，请联系管理员开通账号");
    }

    String normalized = username.trim();
    UserEntity user = userRegistrationService.createUser(
        normalized,
        rawPassword,
        normalized,
        null,
        UserRegistrationService.SELF_REGISTER_ROLES);
    log.info("自助注册成功: username={}", user.getUsername());
    return user.getUsername();
  }

  @Transactional
  public LoginResult login(String username, String rawPassword, String userAgent, String clientIp) {
    UserEntity user = userRepository.findWithRolesByUsername(username)
        // 用户不存在与密码错误返回同一提示，避免暴露账号是否存在
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误"));

    if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
      log.warn("登录失败（密码错误）: username={}, ip={}", username, clientIp);
      throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误");
    }

    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.AUTH_USER_DISABLED, "账号已被禁用，请联系管理员");
    }

    user.setLastLoginAt(LocalDateTime.now());
    userRepository.save(user);

    RefreshTokenService.IssuedRefreshToken refreshToken =
        refreshTokenService.issue(user.getId(), userAgent, clientIp);

    String accessToken = issueAccessToken(user);
    String username0 = user.getUsername();
    CurrentUserResponse currentUser = toCurrentUser(user);
    log.info("登录成功: username={}, ip={}", username0, clientIp);

    return new LoginResult(
        new LoginResponse(accessToken, "Bearer", jwtTokenService.accessTokenTtlSeconds(), currentUser),
        refreshToken.rawToken(),
        authProperties.getRefreshTokenTtl().toSeconds());
  }

  /** 用刷新令牌续期；内部完成令牌轮换 */
  @Transactional
  public LoginResult refresh(String rawRefreshToken, String userAgent, String clientIp) {
    Long userId = refreshTokenService.rotate(rawRefreshToken, userAgent, clientIp);

    UserEntity user = userRepository.findWithRolesById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
    if (user.getStatus() != UserStatus.ACTIVE) {
      refreshTokenService.revokeAllForUser(userId);
      throw new BusinessException(ErrorCode.AUTH_USER_DISABLED, "账号已被禁用，请联系管理员");
    }

    String accessToken = issueAccessToken(user);
    return new LoginResult(
        new LoginResponse(accessToken, "Bearer", jwtTokenService.accessTokenTtlSeconds(), toCurrentUser(user)),
        refreshTokenService.issue(userId, userAgent, clientIp).rawToken(),
        authProperties.getRefreshTokenTtl().toSeconds());
  }

  /**
   * 退出登录：撤销刷新令牌并把当前 Access Token 拉黑（它自然过期前无法再用）。
   */
  public void logout(String rawRefreshToken, String rawAccessToken) {
    refreshTokenService.revoke(rawRefreshToken);
    if (rawAccessToken != null) {
      try {
        Jwt jwt = jwtTokenService.decode(rawAccessToken);
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt != null) {
          tokenRevocationService.blacklist(jwt.getId(), expiresAt);
        }
      } catch (RuntimeException e) {
        log.debug("退出登录时 Access Token 已失效，忽略: {}", e.getMessage());
      }
    }
  }

  @Transactional(readOnly = true)
  public CurrentUserResponse currentUser() {
    AuthUserPrincipal principal = CurrentUserHolder.requireCurrent();
    UserEntity user = userRepository.findWithRolesById(principal.userId())
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
    // displayName 变化不影响令牌有效性，这里以库里的最新值为准
    return toCurrentUser(user);
  }

  /**
   * 修改本人密码：改完立刻让其它端的令牌失效。
   */
  @Transactional
  public void changePassword(String oldPassword, String newPassword) {
    AuthUserPrincipal principal = CurrentUserHolder.requireCurrent();
    UserEntity user = userRepository.findWithRolesById(principal.userId())
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));

    if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
      throw new BusinessException(ErrorCode.AUTH_OLD_PASSWORD_MISMATCH, "原密码不正确");
    }
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setUpdatedAt(LocalDateTime.now());
    userRepository.save(user);

    refreshTokenService.revokeAllForUser(user.getId());
    tokenRevocationService.bumpTokenVersion(user.getId());
  }

  private String issueAccessToken(UserEntity user) {
    List<String> roles = authorityResolver.roleList(user);
    List<String> permissions = authorityResolver.permissionList(user);
    return jwtTokenService.createAccessToken(
        user.getId(), user.getUsername(), user.getTokenVersion(), roles, permissions);
  }

  private CurrentUserResponse toCurrentUser(UserEntity user) {
    return new CurrentUserResponse(
        user.getId(),
        user.getUsername(),
        user.getDisplayName(),
        user.getEmail(),
        authorityResolver.roleList(user),
        authorityResolver.permissionList(user));
  }
}
