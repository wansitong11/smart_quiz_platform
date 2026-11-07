package interview.guide.modules.aicost.service;

import interview.guide.common.ai.AiFeature;
import interview.guide.modules.aicost.AiCostProperties;
import interview.guide.modules.aicost.AiCostProperties.Default;
import interview.guide.modules.aicost.AiCostProperties.FeatureBudget;
import interview.guide.modules.aicost.model.AiUserRateLimitOverrideEntity;
import interview.guide.modules.aicost.repository.AiUserRateLimitOverrideRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 计算用户在某功能上最终生效的 RPM / TPM / 并发限制。
 *
 * <p>优先级：功能级覆盖 &gt; 用户全局覆盖 &gt; 系统功能/默认配置；结果短缓存避免每次模型调用查库。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiUserRateLimitResolver {

  private static final long CACHE_TTL_NANOS = Duration.ofSeconds(60).toNanos();
  private static final int CACHE_MAX_SIZE = 10_000;

  private final AiCostProperties properties;
  private final AiUserRateLimitOverrideRepository overrideRepository;
  private final Map<Key, CacheEntry> cache = new ConcurrentHashMap<>();

  public RateLimits resolve(AiFeature feature, Long userId) {
    RateLimits defaults = defaults(feature);
    if (userId == null) {
      return defaults;
    }
    long nowNanos = System.nanoTime();
    Key key = new Key(feature, userId);
    CacheEntry cached = cache.get(key);
    if (cached != null && cached.expiresAtNanos() > nowNanos) {
      return cached.limits();
    }
    RateLimits limits = compute(feature, userId, defaults);
    cache.put(key, new CacheEntry(limits, nowNanos + CACHE_TTL_NANOS));
    evictExpiredIfNeeded(nowNanos);
    return limits;
  }

  private RateLimits compute(AiFeature feature, Long userId, RateLimits defaults) {
    List<AiUserRateLimitOverrideEntity> overrides = new ArrayList<>();
    overrides.addAll(overrideRepository.findActiveFeature(userId, feature.name(), Instant.now()));
    overrides.addAll(overrideRepository.findActiveGlobal(userId, Instant.now()));
    overrides.sort(Comparator.comparing(o -> o.getFeature() == null ? 0 : 1));

    int rpm = defaults.rpm();
    long tpm = defaults.tpm();
    int concurrency = defaults.concurrency();
    for (AiUserRateLimitOverrideEntity override : overrides) {
      if (override.getRpmLimit() != null) {
        rpm = override.getRpmLimit();
      }
      if (override.getTpmLimit() != null) {
        tpm = override.getTpmLimit();
      }
      if (override.getConcurrencyLimit() != null) {
        concurrency = override.getConcurrencyLimit();
      }
    }
    return new RateLimits(rpm, tpm, concurrency);
  }

  private RateLimits defaults(AiFeature feature) {
    FeatureBudget fb = properties.getBudget().getFeatures().get(feature.name());
    if (fb == null) {
      fb = new FeatureBudget();
    }
    Default d = properties.getBudget().getDefaultRules();
    return new RateLimits(
        fb.getRpm() != null ? fb.getRpm() : d.getRpm(),
        fb.getTpm() != null ? fb.getTpm() : d.getTpm(),
        fb.getConcurrency() != null ? fb.getConcurrency() : d.getConcurrency());
  }

  private void evictExpiredIfNeeded(long nowNanos) {
    if (cache.size() <= CACHE_MAX_SIZE) {
      return;
    }
    cache.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= nowNanos);
    if (cache.size() > CACHE_MAX_SIZE) {
      cache.clear();
    }
  }

  public void invalidate(Long userId) {
    if (userId == null) {
      return;
    }
    cache.keySet().removeIf(key -> userId.equals(key.userId()));
  }

  public record RateLimits(int rpm, long tpm, int concurrency) {
  }

  private record Key(AiFeature feature, Long userId) {
  }

  private record CacheEntry(RateLimits limits, long expiresAtNanos) {
  }
}
