package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("卷末官方答案区解析")
class PaperStructureOfficialAnswerKeyTest {

  @Test
  @DisplayName("按题号解析官方答案并标记 OFFICIAL 来源")
  void shouldParseOfficialAnswersByQuestionNumber() {
    Map<Integer, AnswerKeyDTO> keys = PaperStructureService.parseOfficialAnswerKey(
        """
        1. A
        2、 B
        3：C
        4）D
        """,
        new ArrayList<>());

    assertThat(keys).containsOnlyKeys(0, 1, 2, 3);
    assertThat(keys.values())
        .allSatisfy(key -> assertThat(key.source()).isEqualTo(AnswerKeyDTO.SOURCE_OFFICIAL));
    assertThat(keys.get(0).referenceAnswer()).isEqualTo("A");
    assertThat(keys.get(1).referenceAnswer()).isEqualTo("B");
    assertThat(keys.get(2).referenceAnswer()).isEqualTo("C");
    assertThat(keys.get(3).referenceAnswer()).isEqualTo("D");
  }

  @Test
  @DisplayName("无法识别的行不会生成答案键，并提示解析不完整")
  void shouldIgnoreUnrecognizedLines() {
    List<String> warnings = new ArrayList<>();
    Map<Integer, AnswerKeyDTO> keys = PaperStructureService.parseOfficialAnswerKey(
        "参考答案与解析\n本题略",
        warnings);

    assertThat(keys).isEmpty();
    assertThat(warnings).anyMatch(warning -> warning.contains("官方答案区解析不完整"));
  }

  @Test
  @DisplayName("答案区后面仍有题目时不能吞掉后续节点")
  void shouldNotTreatAnswerBlockAsTrailingWhenQuestionsFollow() {
    BlockNodeDTO answerKey = node("ANSWER_KEY");
    BlockNodeDTO question = node("QUESTION");

    assertThat(PaperStructureService.isDefinitelyTrailingAnswerKey(
        List.of(answerKey, question), answerKey)).isFalse();
    assertThat(PaperStructureService.isDefinitelyTrailingAnswerKey(
        List.of(answerKey), answerKey)).isTrue();
  }

  private BlockNodeDTO node(String type) {
    return new BlockNodeDTO(
        type,
        1,
        1,
        null,
        null,
        null,
        List.of(),
        null,
        List.of(),
        null,
        null,
        null,
        List.of(),
        null);
  }
}
