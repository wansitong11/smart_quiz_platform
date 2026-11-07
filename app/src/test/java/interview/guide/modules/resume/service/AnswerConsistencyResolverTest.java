package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("答案一致性裁决")
class AnswerConsistencyResolverTest {

  private final AnswerConsistencyResolver resolver = new AnswerConsistencyResolver();

  @Test
  @DisplayName("主求解和独立复核一致时采用共同答案")
  void shouldUseConsensusAnswer() {
    AnswerConsistencyResolver.Resolution result = resolver.resolve(
        choiceQuestion(),
        answerKey("B", 72, AnswerKeyDTO.SOURCE_TEXT),
        answerKey("B", 88, AnswerKeyDTO.SOURCE_VISION),
        null
    );

    assertThat(result.finalAnswer()).isEqualTo("B");
    assertThat(result.status()).isEqualTo(AnswerConsistencyResolver.STATUS_CONSENSUS);
    assertThat(result.consistencyScore()).isEqualTo(100);
    assertThat(result.confidence()).isGreaterThanOrEqualTo(88);
  }

  @Test
  @DisplayName("两次冲突后第三次答案形成 2:1 多数裁决")
  void shouldUseMajorityAfterTieBreak() {
    AnswerConsistencyResolver.Resolution result = resolver.resolve(
        choiceQuestion(),
        answerKey("A", 92, AnswerKeyDTO.SOURCE_VISION),
        answerKey("B", 70, AnswerKeyDTO.SOURCE_TEXT),
        answerKey("B", 58, AnswerKeyDTO.SOURCE_VISION)
    );

    assertThat(result.finalAnswer()).isEqualTo("B");
    assertThat(result.status()).isEqualTo(AnswerConsistencyResolver.STATUS_MAJORITY);
    assertThat(result.consistencyScore()).isEqualTo(67);
    assertThat(result.confidence()).isBetween(58, 79);
  }

  @Test
  @DisplayName("三个候选各不相同仍保留最高置信度答案并标记冲突")
  void shouldKeepBestCandidateWhenAllAnswersDiffer() {
    AnswerConsistencyResolver.Resolution result = resolver.resolve(
        choiceQuestion(),
        answerKey("A", 90, AnswerKeyDTO.SOURCE_VISION),
        answerKey("B", 75, AnswerKeyDTO.SOURCE_TEXT),
        answerKey("C", 55, AnswerKeyDTO.SOURCE_VISION)
    );

    assertThat(result.finalAnswer()).isEqualTo("A");
    assertThat(result.status()).isEqualTo(AnswerConsistencyResolver.STATUS_CONFLICT);
    assertThat(result.consistencyScore()).isEqualTo(34);
    assertThat(result.confidence()).isEqualTo(59);
    assertThat(result.answerKey().referenceAnswer()).isNotBlank();
  }

  @Test
  @DisplayName("独立复核缺失时保留主答案并标记单路结果")
  void shouldKeepPrimaryWhenVerificationMissing() {
    AnswerConsistencyResolver.Resolution result = resolver.resolve(
        choiceQuestion(),
        answerKey("B", 80, AnswerKeyDTO.SOURCE_VISION),
        null,
        null
    );

    assertThat(result.finalAnswer()).isEqualTo("B");
    assertThat(result.status()).isEqualTo(AnswerConsistencyResolver.STATUS_SINGLE);
    assertThat(result.confidence()).isEqualTo(80);
  }

  @Test
  @DisplayName("圈号选项按题目实际选项键归一化，不被 A-H 过滤掉")
  void shouldResolveCircledOptionKeys() {
    AnswerConsistencyResolver.Resolution result = resolver.resolve(
        circledChoiceQuestion(),
        answerKey("④", 90, AnswerKeyDTO.SOURCE_TEXT),
        answerKey("D", 85, AnswerKeyDTO.SOURCE_TEXT),
        null
    );

    assertThat(result.finalAnswer()).isEqualTo("④");
    assertThat(result.status()).isEqualTo(AnswerConsistencyResolver.STATUS_CONSENSUS);
    assertThat(result.answerKey().optionCorrectness())
        .containsEntry("①", false)
        .containsEntry("④", true);
  }

  private InterviewQuestionDTO choiceQuestion() {
    List<QuestionOption> options = List.of(
        new QuestionOption("A", "(6-π)/6"),
        new QuestionOption("B", "(12-π)/12"),
        new QuestionOption("C", "π/6"),
        new QuestionOption("D", "π/12")
    );
    return new InterviewQuestionDTO(
        0, "几何概型题", "math", "几何", null, "C", null, null,
        false, null, "A", List.of("几何概型"), null, null, 5,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE, options, null, null, null,
        null, null, null, false);
  }

  private InterviewQuestionDTO circledChoiceQuestion() {
    List<QuestionOption> options = List.of(
        new QuestionOption("①", "It is native to lowlands and marshes."),
        new QuestionOption("②", "It is more closely related to the mule deer."),
        new QuestionOption("③", "It has replaced the black-tailed deer."),
        new QuestionOption("④", "It no longer lives in a particular habitat.")
    );
    return new InterviewQuestionDTO(
        0, "Which of the following is true?", "english", "阅读", null, null, null, null,
        false, null, "暂缺", List.of(), null, null, 3,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE, options, null, 2, 2,
        null, null, null, false);
  }

  private AnswerKeyDTO answerKey(String answer, int confidence, String source) {
    return new AnswerKeyDTO(
        0, answer, Map.of(answer, true), "解析", List.of("考点"), null,
        null, false, confidence, source);
  }
}
