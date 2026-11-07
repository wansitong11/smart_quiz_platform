package interview.guide.common.ai;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 视觉调用的统一重试策略。
 *
 * <p>运输超时和终态错误立即失败；限流、非法 JSON、输出截断和内容不完整才进入有限重试。</p>
 */
public final class VisionRetryPolicy {

  private static final long RATE_LIMIT_BASE_BACKOFF_MS = 300;

  private VisionRetryPolicy() {
  }

  public static boolean shouldRetry(
      VisionFailureClass failureClass,
      int attempt,
      int maxAttempts
  ) {
    return attempt < maxAttempts
        && VisionFailureClassifier.canRetryAfterFailure(failureClass);
  }

  public static void rateLimitBackoff(int attempt) throws InterruptedException {
    long delayMs = RATE_LIMIT_BASE_BACKOFF_MS * Math.max(1, attempt)
        + ThreadLocalRandom.current().nextLong(RATE_LIMIT_BASE_BACKOFF_MS);
    Thread.sleep(delayMs);
  }
}
