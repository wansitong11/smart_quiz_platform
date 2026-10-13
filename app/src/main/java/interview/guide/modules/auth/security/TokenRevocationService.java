package interview.guide.modules.auth.security;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 令牌撤销状态（Redis 承载）。
 * <p>
 * 两类状态：
 * <ul>
 *   <li>Access Token 黑名单：按 jti 记，TTL 到令牌自然过期为止，用于"退出登录 / 单端下线"；</li>
 *   <li>用户令牌版本号：踢人下线时自增，已签发的 Access Token 因 ver 不匹配立即失效。
 *       版本号在 Redis 缓存 5 分钟，踢人时同步失效缓存，避免每次请求都查库。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

  private static final String BLACKLIST_PREFIX = "auth:blacklist:";
  private static final String TOKEN_VERSION_PREFIX = "auth:user-ver:";
  private static final Duration TOKEN_VERSION_CACHE_TTL = Duration.ofMinutes(5);

  private final RedissonClient redissonClient;
  private final UserRepository userRepository;

  // ---------- Access Token 黑名单 ----------

  public void blacklist(String tokenId, Instant expiresAt) {
    Duration ttl = Duration.between(Instant.now(), expiresAt);
    if (ttl.isNegative() || ttl.isZero()) {
      return;
    }
    RBucket<String> bucket = redissonClient.getBucket(BLACKLIST_PREFIX + tokenId, StringCodec.INSTANCE);
    bucket.set("1", ttl);
  }

  public boolean isBlacklisted(String tokenId) {
    if (tokenId == null || tokenId.isBlank()) {
      return false;
    }
    return redissonClient.getBucket(BLACKLIST_PREFIX + tokenId, StringCodec.INSTANCE).isExists();
  }

  // ---------- 用户令牌版本号 ----------

  /**
   * 读取用户当前令牌版本，Redis 未命中时回源数据库并回填。
   *
   * @return 用户不存在时返回 -1（调用方据此拒绝请求）
   */
  @Transactional(readOnly = true)
  public int currentTokenVersion(Long userId) {
    RBucket<String> bucket = redissonClient.getBucket(TOKEN_VERSION_PREFIX + userId, StringCodec.INSTANCE);
    String cached = bucket.get();
    if (cached != null) {
      return Integer.parseInt(cached);
    }

    Optional<UserEntity> user = userRepository.findById(userId);
    if (user.isEmpty()) {
      return -1;
    }
    int version = user.get().getTokenVersion();
    bucket.set(String.valueOf(version), TOKEN_VERSION_CACHE_TTL);
    return version;
  }

  /**
   * 使某用户已签发的全部 Access Token 立即失效（踢人下线）。
   *
   * @return 新版本号
   */
  @Transactional
  public int bumpTokenVersion(Long userId) {
    UserEntity user = userRepository.findById(userId)
        .orElseThrow(() -> new IllegalStateException("用户不存在: " + userId));
    int next = user.getTokenVersion() + 1;
    user.setTokenVersion(next);
    user.setUpdatedAt(java.time.LocalDateTime.now());
    userRepository.save(user);

    redissonClient.getBucket(TOKEN_VERSION_PREFIX + userId, StringCodec.INSTANCE)
        .set(String.valueOf(next), TOKEN_VERSION_CACHE_TTL);
    log.info("已踢下线: userId={}, tokenVersion={}", userId, next);
    return next;
  }
}
