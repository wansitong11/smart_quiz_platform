package interview.guide.modules.aicost.service;

import interview.guide.modules.auth.model.RoleCode;
import interview.guide.modules.auth.repository.UserRepository;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 判断一个模型调用归属用户是否享有管理员豁免。
 *
 * <p>异步切题、批改线程没有 SecurityContext，因此统一按 userId 查角色；查询结果短缓存，
 * 避免每次模型调用都访问数据库。查询失败时按普通用户处理，保留原有保护。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAdminPolicy {

  private static final long CACHE_TTL_NANOS = Duration.ofSeconds(60).toNanos();
  private static final int CACHE_MAX_SIZE = 10_000;

  private final UserRepository userRepository;
  private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

  public boolean isAdmin(Long userId) {
    if (userId == null) {
      return false;
    }
    long now = System.nanoTime();
    CacheEntry cached = cache.get(userId);
    if (cached != null && cached.expiresAtNanos() > now) {
      return cached.admin();
    }
    boolean admin = loadAdmin(userId);
    cache.put(userId, new CacheEntry(admin, now + CACHE_TTL_NANOS));
    evictExpiredIfNeeded(now);
    return admin;
  }

  private boolean loadAdmin(Long userId) {
    try {
      return userRepository.countByIdAndRoleCode(userId, RoleCode.ADMIN.code()) > 0;
    } catch (DataAccessException e) {
      log.warn("[AiAdminPolicy] 查询管理员角色失败，按普通用户处理: userId={}", userId, e);
      return false;
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

  private record CacheEntry(boolean admin, long expiresAtNanos) {
  }
}
