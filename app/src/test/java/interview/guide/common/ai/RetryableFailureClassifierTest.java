package interview.guide.common.ai;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("失败可重试分类")
class RetryableFailureClassifierTest {

  @Test
  @DisplayName("本地限流和超时属于可重试失败")
  void classifiesTransientBusinessErrorsAsRetryable() {
    assertThat(RetryableFailureClassifier.isRetryable(
        new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED, "限流")))
        .isTrue();
    assertThat(RetryableFailureClassifier.isRetryable(
        new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "超时")))
        .isTrue();
  }

  @Test
  @DisplayName("参数和素材类业务失败不可重试")
  void classifiesDeterministicBusinessErrorsAsTerminal() {
    assertThat(RetryableFailureClassifier.isRetryable(
        new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "素材缺失")))
        .isFalse();
  }

  @Test
  @DisplayName("包装在异常链里的限流仍可重试")
  void followsCauseChain() {
    BusinessException wrapped = new BusinessException(
        ErrorCode.INTERVIEW_EVALUATION_FAILED,
        "批改失败",
        new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED, "限流"));

    assertThat(RetryableFailureClassifier.isRetryable(wrapped)).isTrue();
  }

  @Test
  @DisplayName("视觉准入拒绝属于可重试失败")
  void classifiesVisionRejectionAsRetryable() {
    assertThat(RetryableFailureClassifier.isRetryable(
        new VisionRequestRejectedException("vision busy")))
        .isTrue();
  }

  @Test
  @DisplayName("上游 429 属于可重试失败，并读取 Retry-After")
  void classifiesUpstreamRateLimitAndReadsRetryAfter() {
    RateLimitException error = RateLimitException.builder()
        .headers(Headers.builder().put("retry-after", "7").build())
        .build();

    assertThat(RetryableFailureClassifier.isRetryable(error)).isTrue();
    assertThat(RetryableFailureClassifier.isRateLimitFailure(error)).isTrue();
    assertThat(RetryableFailureClassifier.retryAfterMillis(error)).isEqualTo(7_000L);
  }

  @Test
  @DisplayName("本地闸门限流携带窗口剩余时长")
  void readsLocalRateLimitRetryAfter() {
    AiRateLimitExceededException error =
        new AiRateLimitExceededException("本地限流", 12_000);

    assertThat(RetryableFailureClassifier.isRetryable(error)).isTrue();
    assertThat(RetryableFailureClassifier.isRateLimitFailure(error)).isTrue();
    assertThat(RetryableFailureClassifier.retryAfterMillis(error))
        .isEqualTo(12_000L);
  }

  @Test
  @DisplayName("供应商用普通异常包装 429 文案时仍能识别")
  void classifiesRateLimitMessageFromWrappedProviderError() {
    RuntimeException wrapped = new RuntimeException(
        "429: 该模型当前访问量过大，请您稍后再试");

    assertThat(RetryableFailureClassifier.isRetryable(wrapped)).isTrue();
    assertThat(RetryableFailureClassifier.isRateLimitFailure(wrapped)).isTrue();
    assertThat(RetryableFailureClassifier.retryAfterMillis(wrapped)).isZero();
  }
}
