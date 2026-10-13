package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.dto.CreateUserRequest;
import interview.guide.modules.auth.dto.RoleResponse;
import interview.guide.modules.auth.dto.UpdateUserRequest;
import interview.guide.modules.auth.dto.UserResponse;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.model.PermissionEntity;
import interview.guide.modules.auth.repository.RoleRepository;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUserHolder;
import interview.guide.modules.auth.security.TokenRevocationService;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号与角色管理（管理员功能）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;
  private final RefreshTokenService refreshTokenService;
  private final TokenRevocationService tokenRevocationService;
  private final UserRegistrationService userRegistrationService;

  @Transactional(readOnly = true)
  public List<UserResponse> listUsers() {
    return userRepository.findAll().stream()
        .sorted(Comparator.comparing(UserEntity::getId))
        .map(this::toResponse)
        .toList();
  }

  @Transactional
  public UserResponse createUser(CreateUserRequest request) {
    UserEntity user = userRegistrationService.createUser(
        request.username(),
        request.password(),
        request.displayName(),
        request.email(),
        request.roles());
    log.info("创建账号: username={}", user.getUsername());
    return toResponse(user);
  }

  @Transactional
  public UserResponse updateUser(Long id, UpdateUserRequest request) {
    UserEntity user = userRepository.findWithRolesById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));

    if (request.displayName() != null) {
      user.setDisplayName(request.displayName());
    }
    if (request.email() != null) {
      user.setEmail(blankToNull(request.email()));
    }
    if (request.roles() != null && !request.roles().isEmpty()) {
      user.setRoles(userRegistrationService.resolveRoles(request.roles()));
    }

    boolean disabling = request.status() != null && request.status() != user.getStatus();
    if (request.status() != null) {
      Long currentUserId = CurrentUserHolder.currentUserId();
      if (request.status() == UserStatus.DISABLED && currentUserId.equals(user.getId())) {
        throw new BusinessException(ErrorCode.AUTH_CANNOT_DISABLE_SELF, "不能禁用当前登录账号");
      }
      user.setStatus(request.status());
    }
    user.setUpdatedAt(LocalDateTime.now());
    userRepository.save(user);

    // 禁用或改角色后，立即让该账号已签发的令牌失效，避免权限残留
    if (disabling || request.roles() != null) {
      refreshTokenService.revokeAllForUser(user.getId());
      tokenRevocationService.bumpTokenVersion(user.getId());
    }
    return toResponse(user);
  }

  @Transactional
  public void deleteUser(Long id) {
    if (CurrentUserHolder.currentUserId().equals(id)) {
      throw new BusinessException(ErrorCode.AUTH_CANNOT_DELETE_SELF, "不能删除当前登录账号");
    }
    UserEntity user = userRepository.findWithRolesById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
    refreshTokenService.revokeAllForUser(id);
    tokenRevocationService.bumpTokenVersion(id);
    userRepository.delete(user);
    log.info("删除账号: username={}", user.getUsername());
  }

  @Transactional
  public void resetPassword(Long id, String newPassword) {
    UserEntity user = userRepository.findWithRolesById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setUpdatedAt(LocalDateTime.now());
    userRepository.save(user);

    refreshTokenService.revokeAllForUser(id);
    tokenRevocationService.bumpTokenVersion(id);
    log.info("重置密码并强制下线: userId={}", id);
  }

  /**
   * 踢人下线：Access Token 靠版本号立即失效，刷新令牌全部撤销。
   */
  @Transactional
  public void kickOut(Long id) {
    UserEntity user = userRepository.findWithRolesById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
    refreshTokenService.revokeAllForUser(id);
    tokenRevocationService.bumpTokenVersion(id);
    log.info("管理员踢下线: userId={}, username={}", id, user.getUsername());
  }

  @Transactional(readOnly = true)
  public List<RoleResponse> listRoles() {
    return roleRepository.findAll().stream()
        .sorted(Comparator.comparing(RoleEntity::getId))
        .map(role -> new RoleResponse(
            role.getId(),
            role.getCode(),
            role.getName(),
            role.getDescription(),
            role.getSystemRole(),
            role.getPermissions().stream()
                .map(PermissionEntity::getCode)
                .sorted()
                .toList()))
        .toList();
  }

  private UserResponse toResponse(UserEntity user) {
    return new UserResponse(
        user.getId(),
        user.getUsername(),
        user.getDisplayName(),
        user.getEmail(),
        user.getStatus(),
        user.getRoles().stream().map(RoleEntity::getCode).sorted().toList(),
        user.getLastLoginAt(),
        user.getCreatedAt());
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }
}
