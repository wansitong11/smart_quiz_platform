package interview.guide.common.ai;

import interview.guide.modules.resume.model.PageRecognition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.exc.InvalidFormatException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Jackson 结构错误反馈")
class JsonFailureFeedbackFormatterTest {

  private static final String PAGE_8_BAD_JSON = """
      {"pageNo":8,"text":"1\\n2\\n3\\n4\\n5\\n6\\n7\\n8\\n9",
       "lines":[
        {"text":"1","bbox":[0.1,0.05,0.8,0.08]},
        {"text":"2","bbox":[0.1,0.13,0.8,0.08]},
        {"text":"3","bbox":[0.1,0.21,0.8,0.08]},
        {"text":"4","bbox":[0.1,0.29,0.8,0.08]},
        {"text":"5","bbox":[0.1,0.37,0.8,0.08]},
        {"text":"6","bbox":[0.1,0.45,0.8,0.08]},
        {"text":"7","bbox":[0.1,0.53,0.8,0.08]},
        {"text":"8","bbox":[0.1,0.61,0.8,0.08]},
        {"text":"9","bbox":[0.1,0.61,0.8,"bbox"]}],
       "assets":[],"hasFigure":false,"stemFigureAnchors":[],"notes":""}
      """;

  @Test
  @DisplayName("能从第 8 页 bbox 字符串错误中提取路径、期望类型和短值")
  void extractsPathExpectedTypeAndValue() {
    InvalidFormatException error = invalidFormatException();

    JsonFailureFeedback feedback = JsonFailureFeedbackFormatter.extract(error);

    assertThat(feedback).isNotNull();
    assertThat(feedback.location()).contains("lines[8].bbox[3]");
    assertThat(feedback.expected()).contains("double");
    assertThat(feedback.actual()).isEqualTo("\"bbox\"");
  }

  @Test
  @DisplayName("纠正提示包含具体位置且不泄露整段原始回包")
  void instructionIsTargetedAndBounded() {
    JsonFailureFeedback feedback = JsonFailureFeedbackFormatter.extract(invalidFormatException());

    String instruction = JsonFailureFeedbackFormatter.toInstruction(feedback, 200);

    assertThat(instruction)
        .contains("lines[8].bbox[3]")
        .contains("bbox")
        .doesNotContain("hasFigure")
        .doesNotContain("stemFigureAnchors");
    assertThat(instruction.length()).isLessThanOrEqualTo(201);
  }

  @Test
  @DisplayName("非 Jackson 异常返回空反馈")
  void nonJacksonErrorReturnsNull() {
    assertThat(JsonFailureFeedbackFormatter.extract(new IllegalStateException("boom")))
        .isNull();
  }

  private InvalidFormatException invalidFormatException() {
    BeanOutputConverter<PageRecognition> converter =
        new BeanOutputConverter<>(PageRecognition.class);
    try {
      converter.convert(PAGE_8_BAD_JSON);
    } catch (InvalidFormatException e) {
      return e;
    }
    throw new IllegalStateException("fixture 应当触发 InvalidFormatException");
  }
}
