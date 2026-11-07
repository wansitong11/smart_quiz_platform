package interview.guide.common.log;

import interview.guide.modules.resume.model.PageRecognition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("错误日志脱敏")
class ErrorLogSanitizerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("Jackson 坐标解析失败时记录字段路径、非法值和目标类型")
  void includesSafeJacksonDiagnostics() {
    String json = """
        {
          "pageNo": 8,
          "text": "第 8 页",
          "lines": [{"text": "第 8 页", "bbox": ["无法解析", 0.1, 0.8, 0.1]}],
          "assets": [],
          "hasFigure": false,
          "stemFigureAnchors": [],
          "notes": null
        }
        """;

    Throwable error = catchThrowable(
        () -> objectMapper.readValue(json, PageRecognition.class));

    assertThat(ErrorLogSanitizer.summarize(error))
        .contains(
            "InvalidFormatException",
            "path=interview.guide.modules.resume.model.PageRecognition[\"lines\"]"
                + "->java.util.ArrayList[0]"
                + "->interview.guide.modules.resume.model.PageRecognition$PageLine"
                + "[\"bbox\"]->double[][0]",
            "invalidValue=\"无法解析\"",
            "targetType=double");
  }

  @Test
  @DisplayName("保留脱敏后的 cause 链，不输出原始异常 message")
  void keepsSanitizedCauseChain() {
    IllegalStateException root = new IllegalStateException("secret prompt content");
    RuntimeException outer = new RuntimeException("secret outer content", root);

    String summary = ErrorLogSanitizer.summarize(outer);
    Throwable sanitized = ErrorLogSanitizer.forLogging(outer);

    assertThat(summary)
        .contains("RuntimeException", "IllegalStateException")
        .doesNotContain("secret");
    assertThat(sanitized)
        .hasMessageContaining("sanitized-error")
        .hasMessageNotContaining("secret");
    assertThat(sanitized.getCause())
        .isNotNull()
        .hasMessageContaining("IllegalStateException")
        .hasMessageNotContaining("secret");
  }
}
