package interview.guide.modules.resume.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("页面识别 JSON 结构")
class PageRecognitionSchemaTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("返回结构包含题目级 stemFigureAnchors 声明")
  void shouldExposeStemFigureAnchors() throws Exception {
    PageRecognition recognition = new PageRecognition(
        1,
        "2. 如图，选择正确展开图",
        List.of(),
        List.of(),
        true,
        List.of("2. 如图，选择正确展开图"),
        null
    );

    String json = objectMapper.writeValueAsString(recognition);

    assertThat(json).contains("\"stemFigureAnchors\"");
    assertThat(json).contains("2. 如图，选择正确展开图");
  }

  @Test
  @DisplayName("单阶段输出不包含整页 text，并从 lines 派生")
  void oneStageOutputOmitsPageText() throws Exception {
    PageRecognitionOutput output = new PageRecognitionOutput(
        1,
        List.of(new PageRecognition.PageLine(
            "2. 如图，选择正确展开图", new double[]{0.1, 0.2, 0.8, 0.08})),
        List.of(),
        true,
        List.of("2. 如图，选择正确展开图"),
        null
    );

    String json = objectMapper.writeValueAsString(output);
    var root = objectMapper.readTree(json);

    assertThat(root.has("text")).isFalse();
    assertThat(root.has("lines")).isTrue();
    assertThat(output.toPageRecognition().text())
        .isEqualTo("2. 如图，选择正确展开图");
  }
}
