package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.QuestionFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("答案键与切题结果题号对齐")
class PaperStructureAnswerKeyAlignmentTest {

  @Test
  @DisplayName("题号与指纹一致时复用存量答案键")
  void shouldReuseStoredKeysWhenIndexesMatch() {
    InterviewQuestionDTO first = question(0);
    InterviewQuestionDTO second = question(1);
    Map<Integer, AnswerKeyDTO> stored = Map.of(0, key(0), 1, key(1));
    List<String> warnings = new ArrayList<>();

    Map<Integer, AnswerKeyDTO> aligned = PaperStructureService.alignedAnswerKeys(
        List.of(first, second),
        stored,
        Map.of(0, QuestionFingerprint.of(first), 1, QuestionFingerprint.of(second)),
        warnings);

    assertThat(aligned).containsExactlyInAnyOrderEntriesOf(stored);
    assertThat(warnings).isEmpty();
  }

  @Test
  @DisplayName("只有指纹变化的题丢弃答案键，其余题继续复用")
  void shouldDiscardOnlyChangedQuestion() {
    InterviewQuestionDTO first = question(0);
    InterviewQuestionDTO changedSecond = question(1, "题干已变化");
    Map<Integer, AnswerKeyDTO> stored = Map.of(0, key(0), 1, key(1), 2, key(2));
    List<String> warnings = new ArrayList<>();

    Map<Integer, AnswerKeyDTO> aligned = PaperStructureService.alignedAnswerKeys(
        List.of(first, changedSecond),
        stored,
        Map.of(
            0, QuestionFingerprint.of(first),
            1, QuestionFingerprint.of(question(1))),
        warnings);

    assertThat(aligned).containsOnlyKeys(0);
    assertThat(aligned.get(0)).isEqualTo(stored.get(0));
    assertThat(warnings).containsExactly("有 1 道题题干已变化，已重新生成这些题的答案键");
  }

  @Test
  @DisplayName("旧快照没有指纹时保守复用已有答案键")
  void shouldReuseLegacyKeysWithoutFingerprints() {
    List<String> warnings = new ArrayList<>();

    Map<Integer, AnswerKeyDTO> aligned = PaperStructureService.alignedAnswerKeys(
        List.of(question(0), question(1)),
        Map.of(0, key(0), 1, key(1), 2, key(2)),
        warnings);

    assertThat(aligned).containsOnlyKeys(0, 1);
    assertThat(warnings).isEmpty();
  }

  private InterviewQuestionDTO question(int index) {
    return question(index, "第" + index + "题");
  }

  private InterviewQuestionDTO question(int index, String stem) {
    return new InterviewQuestionDTO(
        index, stem, "未分类", "未分类", null, null, null, null,
        false, null, "暂缺", List.of(), null, null, 4,
        InterviewQuestionDTO.TYPE_FILL_BLANK, null, null, 1, 1,
        null, null, null, false);
  }

  private AnswerKeyDTO key(int index) {
    return new AnswerKeyDTO(index, "答案" + index, Map.of(), null, List.of(),
        null, null, false, 90, AnswerKeyDTO.SOURCE_TEXT);
  }
}
