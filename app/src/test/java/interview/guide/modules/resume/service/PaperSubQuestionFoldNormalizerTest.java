package interview.guide.modules.resume.service;

import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("子题与空位折叠归一化")
class PaperSubQuestionFoldNormalizerTest {

  @Test
  @DisplayName("有分父题覆盖的无分空位和子问被折叠，只保留父题")
  void shouldFoldUnscoredChildrenCoveredByScoredParent() {
    List<BlockNodeDTO> normalized = PaperSubQuestionFoldNormalizer.normalize(List.of(
        node("QUESTION", 8, 13, 3),
        node("SUB_QUESTION", 9, 9, null),
        node("SUB_QUESTION", 10, 11, null),
        node("BLANK", 12, 12, null)
    ), List.of());

    assertThat(normalized).extracting(BlockNodeDTO::type)
        .containsExactly("QUESTION");
  }

  @Test
  @DisplayName("有独立分值的子问保留，无分空位仍被折叠")
  void shouldKeepScoredSubQuestionAndFoldUnscoredBlank() {
    List<BlockNodeDTO> normalized = PaperSubQuestionFoldNormalizer.normalize(List.of(
        node("QUESTION", 20, 26, null),
        node("BLANK", 21, 21, null),
        node("SUB_QUESTION", 23, 24, 2)
    ), List.of());

    assertThat(normalized).extracting(BlockNodeDTO::type)
        .containsExactly("QUESTION", "SUB_QUESTION");
    assertThat(normalized.get(1).fullScore()).isEqualTo(2);
  }

  @Test
  @DisplayName("没有被父题覆盖的空位保留为独立题")
  void shouldKeepBlankWithoutCoveringQuestion() {
    List<BlockNodeDTO> normalized = PaperSubQuestionFoldNormalizer.normalize(List.of(
        node("MATERIAL", 1, 10, null),
        node("BLANK", 3, 3, null)
    ), List.of());

    assertThat(normalized).extracting(BlockNodeDTO::type)
        .containsExactly("MATERIAL", "BLANK");
  }

  @Test
  @DisplayName("相同类型和行号范围的重复节点只保留一个")
  void shouldDeduplicateSameTypeAndLineRange() {
    List<BlockNodeDTO> normalized = PaperSubQuestionFoldNormalizer.normalize(List.of(
        node("BLANK", 16, 16, null),
        node("BLANK", 16, 16, null),
        node("BLANK", 16, 16, null),
        node("QUESTION", 21, 21, 4),
        node("QUESTION", 21, 21, 4)
    ), List.of());

    assertThat(normalized).extracting(BlockNodeDTO::type)
        .containsExactly("BLANK", "QUESTION");
  }

  private BlockNodeDTO node(String type, int start, int end, Integer fullScore) {
    return new BlockNodeDTO(type, start, end, null, null, fullScore, List.of(),
        null, List.of(), null, null, null, List.of(), null);
  }
}
