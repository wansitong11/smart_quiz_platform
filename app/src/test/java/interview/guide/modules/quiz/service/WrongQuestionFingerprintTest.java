package interview.guide.modules.quiz.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.quiz.model.WrongQuestionSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WrongQuestionFingerprintTest {

  @Test
  @DisplayName("空白与选项顺序变化不影响错题指纹")
  void keepsFingerprintStableForWhitespaceAndOptionOrder() {
    InterviewQuestionDTO first = question("下列 哪项 正确？", List.of(
        new InterviewQuestionDTO.QuestionOption("A", "甲"),
        new InterviewQuestionDTO.QuestionOption("B", "乙")));
    InterviewQuestionDTO second = question(" 下列哪项正确？ ", List.of(
        new InterviewQuestionDTO.QuestionOption("B", "乙"),
        new InterviewQuestionDTO.QuestionOption("A", "甲")));

    String firstKey = WrongQuestionFingerprint.of("math", first, List.of());
    String secondKey = WrongQuestionFingerprint.of("math", second, List.of());

    assertThat(firstKey).isEqualTo(secondKey);
  }

  @Test
  @DisplayName("不同阅读材料不会合并为同一道错题")
  void separatesQuestionsByMaterial() {
    InterviewQuestionDTO question = new InterviewQuestionDTO(
        0, "文章主旨是什么？", "kp", "知识点", null, null, null, null, false, null,
        "A", List.of(), null, null, 5, InterviewQuestionDTO.TYPE_READING,
        List.of(), "解析", null, null, null, null, null, false);
    List<WrongQuestionSnapshot.MaterialBlock> firstMaterial =
        List.of(new WrongQuestionSnapshot.MaterialBlock("材料一", "第一篇文章"));
    List<WrongQuestionSnapshot.MaterialBlock> secondMaterial =
        List.of(new WrongQuestionSnapshot.MaterialBlock("材料一", "第二篇文章"));

    String firstKey = WrongQuestionFingerprint.of("english", question, firstMaterial);
    String secondKey = WrongQuestionFingerprint.of("english", question, secondMaterial);

    assertThat(firstKey).isNotEqualTo(secondKey);
  }

  @Test
  @DisplayName("非阅读题不因材料上下文变化拆成多条错题")
  void ignoresMaterialsForNonReadingQuestions() {
    InterviewQuestionDTO question = question("同一道题干", List.of());
    List<WrongQuestionSnapshot.MaterialBlock> firstMaterial =
        List.of(new WrongQuestionSnapshot.MaterialBlock("材料一", "第一段上下文"));
    List<WrongQuestionSnapshot.MaterialBlock> secondMaterial =
        List.of(new WrongQuestionSnapshot.MaterialBlock("材料一", "第二段上下文"));

    assertThat(WrongQuestionFingerprint.of("math", question, firstMaterial))
        .isEqualTo(WrongQuestionFingerprint.of("math", question, secondMaterial));
  }

  @Test
  @DisplayName("阅读题的章节标题也参与指纹")
  void includesReadingSectionTitleInFingerprint() {
    InterviewQuestionDTO question = new InterviewQuestionDTO(
        0, "文章主旨是什么？", "kp", "知识点", null, null, null, null, false, null,
        "A", List.of(), null, null, 5, InterviewQuestionDTO.TYPE_READING,
        List.of(), "解析", null, null, null, null, null, false);
    List<WrongQuestionSnapshot.MaterialBlock> materials =
        List.of(new WrongQuestionSnapshot.MaterialBlock("材料一", "同一篇文章"));
    WrongQuestionSnapshot first = new WrongQuestionSnapshot(
        question, "第一节", null, materials, null, List.of());
    WrongQuestionSnapshot second = new WrongQuestionSnapshot(
        question, "第二节", null, materials, null, List.of());

    assertThat(WrongQuestionFingerprint.of("english", question, first))
        .isNotEqualTo(WrongQuestionFingerprint.of("english", question, second));
  }

  @Test
  @DisplayName("科目或题型不同不会合并为同一道错题")
  void separatesQuestionsBySubjectAndType() {
    InterviewQuestionDTO choice = question("同一道题干", List.of());
    InterviewQuestionDTO otherType = new InterviewQuestionDTO(
        choice.questionIndex(), choice.question(), choice.type(), choice.category(),
        choice.topicSummary(), choice.userAnswer(), choice.score(), choice.feedback(),
        choice.isFollowUp(), choice.parentQuestionIndex(), choice.referenceAnswer(),
        choice.keyPoints(), choice.scoringRubric(), choice.sourceContext(), choice.fullScore(),
        "FILL_BLANK", choice.options(), choice.analysis(), choice.sourcePageFrom(),
        choice.sourcePageTo(), choice.visualContext(), choice.answerKeySource(),
        choice.answerKeyConfidence(), choice.requiresVisualGrading());

    assertThat(WrongQuestionFingerprint.of("math", choice, List.of()))
        .isNotEqualTo(WrongQuestionFingerprint.of("english", choice, List.of()));
    assertThat(WrongQuestionFingerprint.of("math", choice, List.of()))
        .isNotEqualTo(WrongQuestionFingerprint.of("math", otherType, List.of()));
  }

  private InterviewQuestionDTO question(
      String stem, List<InterviewQuestionDTO.QuestionOption> options) {
    return new InterviewQuestionDTO(
        0, stem, "kp", "知识点", null, null, null, null, false, null,
        "A", List.of(), null, null, 5, InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        options, "解析", null, null, null, null, null, false);
  }
}
