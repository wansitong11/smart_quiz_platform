package interview.guide.modules.aicost.service;

import interview.guide.common.ai.AiUserTierResolver;
import interview.guide.modules.auth.model.UserTier;
import interview.guide.modules.auth.repository.UserRepository;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 用户档位与豁免判定。
 *
 * <p>管理员与授权用户都豁免额度；免费档用户占用月度总量池。结果短缓存，
 * 避免每次模型调用都查库；管理员判定复用 {@link AiAdminPolicy} 的缓存。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserAccessPolicy implements AiUserTierResolver {

  private static final long CACHE_TTL_NANOS = Duration.ofSeconds(60).toNanos();
  private static final int CACHE_MAX_SIZE = 10_000;

  private final UserRepository userRepository;
  private final AiAdminPolicy adminPolicy;
  private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

  /** 管理员或授权用户：不限额度、不占总量池。 */
  public boolean isExempt(Long userId) {
    return adminPolicy.isAdmin(userId) || tier(userId) == UserTier.AUTHORIZED;
  }

  @Override
  public boolean isFreeTier(Long userId) {
    if (userId == null || adminPolicy.isAdmin(userId)) {
      return false;
    }
    return tier(userId) == UserTier.FREE;
  }

  @Override
  public boolean isAuthorizedTier(Long userId) {
    if (userId == null || adminPolicy.isAdmin(userId)) {
      return false;
    }
    return tier(userId) == UserTier.AUTHORIZED;
  }

  public UserTier tier(Long userId) {
    if (userId == null) {
      return UserTier.FREE;
    }
    long now = System.nanoTime();
    CacheEntry cached = cache.get(userId);
    if (cached != null && cached.expiresAtNanos() > now) {
      return cached.tier();
    }
    UserTier tier = loadTier(userId);
    cache.put(userId, new CacheEntry(tier, now + CACHE_TTL_NANOS));
    evictExpiredIfNeeded(now);
    return tier;
  }

  /** 管理员修改档位后立即失效缓存，避免 60 秒内仍按旧档位计费/拦截。 */
  public void invalidate(Long userId) {
    if (userId != null) {
      cache.remove(userId);
    }
  }

  private UserTier loadTier(Long userId) {
    try {
      return userRepository.findTierById(userId).orElse(UserTier.FREE);
    } catch (DataAccessException e) {
      log.warn("[UserAccessPolicy] 查询用户档位失败，按免费档处理: userId={}", userId, e);
      return UserTier.FREE;
    }
  }

  private void evictExpiredIfNeeded(long now) {
    if (cache.size() <= CACHE_MAX_SIZE) {
      return;
    }
    cache.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
    if (cache.size() > CACHE_MAX_SIZE) {
      cache.clear();
    }
  }

  private record CacheEntry(UserTier tier, long expiresAtNanos) {
  }
}
