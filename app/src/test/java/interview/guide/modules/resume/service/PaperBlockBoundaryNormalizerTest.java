package interview.guide.modules.resume.service;

import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("试卷结构题目行号校正")
class PaperBlockBoundaryNormalizerTest {

  @Test
  @DisplayName("下一题起始行误落到上一题选项区时，按题号回正并消除重叠")
  void shouldAlignQuestionStartAndClampPreviousQuestion() {
    List<String> lines = List.of(
        "知识点 2 正方体的折叠",
        "5 与“全”相对的汉字是 ( )",
        "庆 祝 全 运 会 !",
        "A. 庆 B. 祝 C. 运 D. 会",
        "6 [2026 辽宁锦州期中] 图(1)是由五个正方形纸片拼接而成的",
        "图(1) 图(2)"
    );
    BlockNodeDTO question5 = question(
        2, 5, "5 与“全”相对的汉字是 ( )");
    BlockNodeDTO question6 = question(
        4, 6, "6 [2026 辽宁锦州期中] 图(1)是由五个正方形纸片拼接而成的");

    List<BlockNodeDTO> result = PaperBlockBoundaryNormalizer.normalize(
        List.of(question5, question6), lines);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).startLine()).isEqualTo(2);
    assertThat(result.get(0).endLine()).isEqualTo(4);
    assertThat(result.get(1).startLine()).isEqualTo(5);
    assertThat(result.get(1).endLine()).isEqualTo(6);
  }

  private BlockNodeDTO question(int startLine, int endLine, String title) {
    return new BlockNodeDTO(
        "QUESTION",
        startLine,
        endLine,
        title,
        "CHOICE",
        3,
        List.of(),
        null,
        List.of(),
        null,
        null,
        "QUESTION",
        List.of(),
        "TEXT"
    );
  }
}
