package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("试卷页失败信息")
class PaperRecognitionServiceErrorTest {

  @Test
  @DisplayName("保留应用生成的业务异常文案")
  void keepsSafeBusinessMessage() {
    RuntimeException cause = new RuntimeException("请求体包含用户试卷内容");
    BusinessException error = new BusinessException(
        ErrorCode.PAPER_RECOGNITION_FAILED,
        "第 3 页识别失败：请稍后重试",
        cause);

    assertThat(PaperRecognitionService.pageFailureMessage(error))
        .isEqualTo("第 3 页识别失败：请稍后重试")
        .doesNotContain("用户试卷内容");
  }

  @Test
  @DisplayName("外部异常不把原始 message 写入页面错误")
  void hidesExternalMessage() {
    RuntimeException error = new RuntimeException("请求体包含用户试卷内容");

    assertThat(PaperRecognitionService.pageFailureMessage(error))
        .isEqualTo("RuntimeException")
        .doesNotContain("用户试卷内容");
  }

  @Test
  @DisplayName("空白业务异常文案回退到异常类型")
  void fallsBackForBlankBusinessMessage() {
    BusinessException error = new BusinessException(
        ErrorCode.PAPER_RECOGNITION_FAILED,
        " ");

    assertThat(PaperRecognitionService.pageFailureMessage(error))
        .isEqualTo("BusinessException");
  }
}
