package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.InterviewEvaluationProperties;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("客观题确定性判分")
class ObjectiveAnswerGraderTest {

  private final InterviewEvaluationProperties properties = new InterviewEvaluationProperties();
  private final ObjectiveAnswerGrader grader = new ObjectiveAnswerGrader(properties);

  private static final List<QuestionOption> OPTIONS = List.of(
      new QuestionOption("A", "甲"), new QuestionOption("B", "乙"),
      new QuestionOption("C", "丙"), new QuestionOption("D", "丁"));

  private InterviewQuestionDTO choice(String questionType, String reference, String userAnswer) {
    return choice(questionType, reference, userAnswer, null);
  }

  private InterviewQuestionDTO choice(String questionType, String reference, String userAnswer,
                                      String analysis) {
    return new InterviewQuestionDTO(0, "题干", questionType, "集合、不等式与向量", null, userAnswer,
        null, null, false, null, reference, List.of("集合的补集运算"), "选对得分", null, 5, questionType,
        OPTIONS, analysis);
  }

  private InterviewQuestionDTO fillBlank(String reference, String userAnswer) {
    return new InterviewQuestionDTO(0, "补写句子", InterviewQuestionDTO.TYPE_FILL_BLANK, "名篇名句", null,
        userAnswer, null, null, false, null, reference, List.of(), "写对得分", null, 3,
        InterviewQuestionDTO.TYPE_FILL_BLANK, null, null);
  }

  private InterviewQuestionDTO subjective(String userAnswer) {
    return new InterviewQuestionDTO(0, "请分析表达效果", InterviewQuestionDTO.TYPE_SHORT_ANSWER, "现代文阅读",
        null, userAnswer, null, null, false, null, "参考答案要点", List.of("要点一"), "踩点给分", null, 6,
        InterviewQuestionDTO.TYPE_SHORT_ANSWER, null, null);
  }

  @Nested
  @DisplayName("选择题")
  class ChoiceTests {

    @Test
    @DisplayName("单选选对得满分，选错与未作答得 0 分")
    void shouldGradeSingleChoice() {
      assertThat(grader.grade(choice("SINGLE_CHOICE", "C", "C")))
          .get().extracting(ObjectiveAnswerGrader.Grade::scoreRate).isEqualTo(100);

      ObjectiveAnswerGrader.Grade wrong =
          grader.grade(choice("SINGLE_CHOICE", "C", "A")).orElseThrow();
      assertThat(wrong.scoreRate()).isZero();
      assertThat(wrong.feedback()).contains("正确答案 C");

      ObjectiveAnswerGrader.Grade blank =
          grader.grade(choice("SINGLE_CHOICE", "C", "")).orElseThrow();
      assertThat(blank.scoreRate()).isZero();
      assertThat(blank.feedback()).contains("未作答");
    }

    @Test
    @DisplayName("多选全对得满分，漏选按配置给部分分，错选不得分")
    void shouldGradeMultiChoice() {
      ObjectiveAnswerGrader.Grade full =
          grader.grade(choice("MULTI_CHOICE", "ABC", "C、A、B")).orElseThrow();
      assertThat(full.scoreRate()).isEqualTo(100);

      ObjectiveAnswerGrader.Grade partial =
          grader.grade(choice("MULTI_CHOICE", "ABC", "AB")).orElseThrow();
      assertThat(partial.scoreRate()).isEqualTo(properties.getMultiChoicePartialCreditRate());
      assertThat(partial.feedback()).contains("漏选");

      ObjectiveAnswerGrader.Grade wrong =
          grader.grade(choice("MULTI_CHOICE", "ABC", "ABD")).orElseThrow();
      assertThat(wrong.scoreRate()).isZero();
      assertThat(wrong.feedback()).contains("错选");
    }

    @Test
    @DisplayName("关闭部分分后漏选同样不得分")
    void shouldDisablePartialCredit() {
      InterviewEvaluationProperties strict = new InterviewEvaluationProperties();
      strict.setMultiChoicePartialCredit(false);
      ObjectiveAnswerGrader strictGrader = new ObjectiveAnswerGrader(strict);

      assertThat(strictGrader.grade(choice("MULTI_CHOICE", "ABC", "AB")).orElseThrow().scoreRate())
          .isZero();
    }

    @Test
    @DisplayName("参考答案不是选项字母时交回模型判分")
    void shouldFallbackWhenReferenceIsNotKeys() {
      assertThat(grader.grade(choice("SINGLE_CHOICE", "因为结构决定性质", "A"))).isEmpty();
    }

    @Test
    @DisplayName("评语带解析与考点，没有解析时退回考点与评分标准")
    void shouldAppendAnalysisAndKnowledgePoints() {
      ObjectiveAnswerGrader.Grade graded = grader.grade(choice("SINGLE_CHOICE", "C", "B",
          "A 的补集与 B 无交集，故选 C；B 项忽略了开闭区间。")).orElseThrow();

      assertThat(graded.feedback())
          .contains("选择错误：你选了 B，正确答案 C。")
          .contains("解析：A 的补集与 B 无交集")
          .contains("考点：集合的补集运算");

      // 没有解析时，评语里至少还有考点，避免只剩一句对错
      assertThat(grader.grade(choice("SINGLE_CHOICE", "C", "B")).orElseThrow().feedback())
          .contains("考点：集合的补集运算")
          .doesNotContain("解析：");
    }
  }

  @Nested
  @DisplayName("填空题")
  class FillBlankTests {

    @Test
    @DisplayName("忽略空白与标点后命中标准答案即得满分")
    void shouldIgnoreSpacingAndPunctuation() {
      assertThat(grader.grade(fillBlank("一览众山小", "一览众山小。")).orElseThrow().scoreRate())
          .isEqualTo(100);
      assertThat(grader.grade(fillBlank("一览众山小", " 一览众山小 ")).orElseThrow().scoreRate())
          .isEqualTo(100);
    }

    @Test
    @DisplayName("纯数字或字母答案直接判错，不进入模型兜底")
    void shouldScorePlainAnswerDirectly() {
      ObjectiveAnswerGrader.Grade grade =
          grader.grade(fillBlank("1/2", "0.5")).orElseThrow();
      assertThat(grade.scoreRate()).isZero();
      assertThat(grade.feedback()).contains("填写错误");
    }

    @Test
    @DisplayName("文字答案未命中时交给模型判断等价性")
    void shouldHandTextAnswerToModel() {
      assertThat(grader.grade(fillBlank("表达了思乡之情", "抒发了对故乡的思念"))).isEmpty();
    }
  }

  @Nested
  @DisplayName("主观题")
  class SubjectiveTests {

    @Test
    @DisplayName("简答题不走确定性判分")
    void shouldSkipSubjective() {
      assertThat(grader.grade(subjective("运用比喻……"))).isEmpty();
      assertThat(grader.grade(subjective(""))).isEmpty();
    }
  }
}
