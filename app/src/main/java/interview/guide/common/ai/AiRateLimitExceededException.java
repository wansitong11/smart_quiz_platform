package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;

/**
 * 本地 AI 调用闸门拒绝时携带建议等待时长。
 */
public class AiRateLimitExceededException extends BusinessException {

  private final long retryAfterMs;

  public AiRateLimitExceededException(String message, long retryAfterMs) {
    super(ErrorCode.AI_RATE_LIMIT_EXCEEDED, message);
    this.retryAfterMs = Math.max(0, retryAfterMs);
  }

  public long retryAfterMs() {
    return retryAfterMs;
  }
}
