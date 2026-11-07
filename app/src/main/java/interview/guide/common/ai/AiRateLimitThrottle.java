package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 同一模型的上游限流共享冷却。
 *
 * <p>免费模型和显式配置并发上限的 Provider 都可能是共享池，单个调用退避只能保护自己；
 * 这里把一次 429 的冷却结束时间共享给同一 provider / model / modality 的后续调用，
 * 避免多个 worker 连续撞击。</p>
 */
@Component
public class AiRateLimitThrottle {

  private final AiRateLimitThrottleProperties properties;
  private final ConcurrentHashMap<Key, Long> throttleUntil = new ConcurrentHashMap<>();

  public AiRateLimitThrottle(AiRateLimitThrottleProperties properties) {
    this.properties = properties;
  }

  public void noteFailure(String providerId, String model, String modality, Throwable error) {
    if (!properties.isEnabled() || error == null
        || !RetryableFailureClassifier.isRateLimitFailure(error)) {
      return;
    }
    long retryAfter = RetryableFailureClassifier.retryAfterMillis(error);
    long delay = retryAfter > 0 ? retryAfter : fallbackDelay();
    delay = Math.min(delay, Math.max(0, properties.getMaxWaitMs()));
    throttleUntil.merge(
        key(providerId, model, modality),
        System.currentTimeMillis() + delay,
        Math::max);
  }

  public void awaitOpen(String providerId, String model, String modality) {
    if (!properties.isEnabled()) {
      return;
    }
    Long until = throttleUntil.get(key(providerId, model, modality));
    if (until == null) {
      return;
    }
    long waitMs = until - System.currentTimeMillis();
    if (waitMs <= 0) {
      throttleUntil.remove(key(providerId, model, modality), until);
      return;
    }
    try {
      sleep(waitMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED,
          "等待模型冷却被中断，请稍后重试", e);
    }
  }

  protected void sleep(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }

  private long fallbackDelay() {
    long jitter = properties.getFallbackJitterMs() <= 0
        ? 0
        : ThreadLocalRandom.current().nextLong(properties.getFallbackJitterMs() + 1);
    return Math.max(0, properties.getFallbackBaseMs()) + jitter;
  }

  private Key key(String providerId, String model, String modality) {
    return new Key(normalize(providerId), normalize(model), normalizeModality(modality));
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private String normalizeModality(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private record Key(String providerId, String model, String modality) {
  }
}
