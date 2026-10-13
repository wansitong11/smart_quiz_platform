package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.model.RefreshTokenEntity;
import interview.guide.modules.auth.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 刷新令牌（Refresh Token）服务。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>客户端持有 {@code tokenId.secret}，服务端只存 secret 的 SHA-256，库泄露也无法冒用；</li>
 *   <li>每次续期都轮换（Rotation）：旧令牌立即撤销并记录 {@code replacedBy}，
 *       旧令牌再次使用即视为泄露，直接撤销该用户全部刷新令牌；</li>
 *   <li>白名单模式，天然支持"踢人下线"。 </li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int SECRET_BYTES = 32;

  private final RefreshTokenRepository refreshTokenRepository;
  private final AuthProperties authProperties;

  public record IssuedRefreshToken(String rawToken, LocalDateTime expiresAt) {
  }

  @Transactional
  public IssuedRefreshToken issue(Long userId, String userAgent, String clientIp) {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime expiresAt = now.plus(authProperties.getRefreshTokenTtl());

    String tokenId = UUID.randomUUID().toString().replace("-", "");
    String secret = randomSecret();

    RefreshTokenEntity entity = new RefreshTokenEntity();
    entity.setUserId(userId);
    entity.setTokenId(tokenId);
    entity.setTokenHash(sha256(secret));
    entity.setUserAgent(truncate(userAgent, 255));
    entity.setClientIp(truncate(clientIp, 64));
    entity.setIssuedAt(now);
    entity.setExpiresAt(expiresAt);
    refreshTokenRepository.save(entity);

    return new IssuedRefreshToken(tokenId + "." + secret, expiresAt);
  }

  /**
   * 校验并轮换刷新令牌。
   *
   * @return 令牌所属用户 id
   */
  @Transactional
  public Long rotate(String rawToken, String userAgent, String clientIp) {
    ParsedToken parsed = parse(rawToken);

    RefreshTokenEntity entity = refreshTokenRepository.findByTokenId(parsed.tokenId())
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "登录已过期，请重新登录"));

    LocalDateTime now = LocalDateTime.now();
    if (!entity.getTokenHash().equals(sha256(parsed.secret()))) {
      // 令牌 id 命中但 secret 不对：疑似伪造，直接清空该用户全部刷新令牌
      log.warn("刷新令牌 secret 校验失败，撤销该用户全部刷新令牌: userId={}", entity.getUserId());
      refreshTokenRepository.revokeAllByUserId(entity.getUserId(), now);
      throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "登录已过期，请重新登录");
    }

    if (entity.getRevokedAt() != null) {
      // 已撤销的令牌被再次使用：视为令牌泄露，全部下线
      log.warn("已撤销的刷新令牌被重复使用，撤销该用户全部刷新令牌: userId={}, tokenId={}",
          entity.getUserId(), parsed.tokenId());
      refreshTokenRepository.revokeAllByUserId(entity.getUserId(), now);
      throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "登录状态异常，请重新登录");
    }

    if (!entity.getExpiresAt().isAfter(now)) {
      throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "登录已过期，请重新登录");
    }

    entity.setRevokedAt(now);
    IssuedRefreshToken rotated = issue(entity.getUserId(), userAgent, clientIp);
    entity.setReplacedBy(rotated.rawToken().substring(0, rotated.rawToken().indexOf('.')));
    refreshTokenRepository.save(entity);

    return entity.getUserId();
  }

  @Transactional
  public void revoke(String rawToken) {
    try {
      ParsedToken parsed = parse(rawToken);
      refreshTokenRepository.findByTokenId(parsed.tokenId()).ifPresent(entity -> {
        if (entity.getRevokedAt() == null) {
          entity.setRevokedAt(LocalDateTime.now());
          refreshTokenRepository.save(entity);
        }
      });
    } catch (BusinessException e) {
      log.debug("退出登录时刷新令牌格式非法，忽略: {}", e.getMessage());
    }
  }

  @Transactional
  public void revokeAllForUser(Long userId) {
    refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
  }

  @Transactional
  public int purgeExpired() {
    return refreshTokenRepository.deleteExpiredBefore(LocalDateTime.now());
  }

  private ParsedToken parse(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "登录已过期，请重新登录");
    }
    int separator = rawToken.indexOf('.');
    if (separator <= 0 || separator == rawToken.length() - 1) {
      throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "登录已过期，请重新登录");
    }
    return new ParsedToken(rawToken.substring(0, separator), rawToken.substring(separator + 1));
  }

  private record ParsedToken(String tokenId, String secret) {
  }

  private String randomSecret() {
    byte[] bytes = new byte[SECRET_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  private String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
