package interview.guide.common.ai;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.UnexpectedEndOfInputException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("视觉失败分类")
class VisionFailureClassifierTest {

  @Test
  @DisplayName("连接异常归类为运输超时")
  void classifiesTransportFailureAsTimeout() {
    assertThat(VisionFailureClassifier.classify(new OpenAIIoException("timeout")))
        .isEqualTo(VisionFailureClass.TRANSIENT_TIMEOUT);
  }

  @Test
  @DisplayName("HTTP 429 归类为限流")
  void classifiesRateLimit() {
    OpenAIServiceException error = mock(OpenAIServiceException.class);
    when(error.statusCode()).thenReturn(429);

    assertThat(VisionFailureClassifier.classify(error))
        .isEqualTo(VisionFailureClass.RATE_LIMITED);
  }

  @Test
  @DisplayName("本地 AI 限流错误也归类为限流")
  void classifiesLocalRateLimit() {
    BusinessException error = new BusinessException(
        ErrorCode.AI_RATE_LIMIT_EXCEEDED, "本地 RPM 已满");

    assertThat(VisionFailureClassifier.classify(error))
        .isEqualTo(VisionFailureClass.RATE_LIMITED);
    assertThat(VisionFailureClassifier.canRetryAfterFailure(
        VisionFailureClass.RATE_LIMITED)).isTrue();
  }

  @Test
  @DisplayName("视觉准入拒绝归类为限流")
  void classifiesVisionRejection() {
    assertThat(VisionFailureClassifier.classify(
        new VisionRequestRejectedException("vision busy")))
        .isEqualTo(VisionFailureClass.RATE_LIMITED);
  }

  @Test
  @DisplayName("JSON 解析异常归类为非法输出")
  void classifiesInvalidJsonOutput() {
    BusinessException error = new BusinessException(
        ErrorCode.PAPER_RECOGNITION_FAILED,
        "第 1 页识别失败：请稍后重试",
        mock(JacksonException.class));

    assertThat(VisionFailureClassifier.classify(error))
        .isEqualTo(VisionFailureClass.INVALID_OUTPUT);
  }

  @Test
  @DisplayName("JSON 在结束前截断归类为输出截断")
  void classifiesTruncatedOutput() {
    BusinessException error = new BusinessException(
        ErrorCode.PAPER_RECOGNITION_FAILED,
        "第 1 页识别失败：请稍后重试",
        mock(UnexpectedEndOfInputException.class));

    assertThat(VisionFailureClassifier.classify(error))
        .isEqualTo(VisionFailureClass.OUTPUT_TRUNCATED);
    assertThat(VisionFailureClassifier.canRetryAfterFailure(
        VisionFailureClass.OUTPUT_TRUNCATED)).isTrue();
  }

  @Test
  @DisplayName("未知异常归类为终态，避免无界重试")
  void classifiesUnknownFailureAsTerminal() {
    assertThat(VisionFailureClassifier.classify(new IllegalStateException("bad config")))
        .isEqualTo(VisionFailureClass.TERMINAL);
  }
}
