package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("答案键文本规范化")
class AnswerKeyPreparationServiceTest {

  @Test
  @DisplayName("解析压缩空白并限制在最大字符数内")
  void shouldLimitAnalysisLength() {
    String source = "结论：答案是 B。\n\n原因：" + "图示可以折叠成完整正方体。".repeat(80);

    String limited = AnswerKeyPreparationService.limitText(source, 180);

    assertThat(limited).doesNotContain("\n");
    assertThat(limited.codePointCount(0, limited.length())).isLessThanOrEqualTo(181);
    assertThat(limited).endsWith("。");
  }

  @Test
  @DisplayName("达到长度上限时在完整句末停止，不再显示截断省略号")
  void shouldEndAtCompleteSentenceInsteadOfEllipsis() {
    String source = "第一句说明答案结论。".repeat(30)
        + "最后补一句完整说明。";

    String limited = AnswerKeyPreparationService.limitText(source, 120);

    assertThat(limited).endsWith("。");
    assertThat(limited).doesNotEndWith("…");
    assertThat(limited.length()).isLessThanOrEqualTo(120);
  }

  @Test
  @DisplayName("带圈序号统一转换为阿拉伯数字")
  void shouldNormalizeOrdinalAnswer() {
    assertThat(AnswerKeyPreparationService.normalizeOrdinalAnswer("③")).isEqualTo("3");
    assertThat(AnswerKeyPreparationService.normalizeOrdinalAnswer("①②")).isEqualTo("12");
  }

  @Test
  @DisplayName("加载旧答案键时压缩解析但保留标准答案")
  void shouldConcisePersistedAnswerWithoutChangingAnswer() {
    AnswerKeyDTO source = new AnswerKeyDTO(
        0, "B", Map.of("B", true), "很长的解析。".repeat(200), List.of(),
        null, null, false, 90, AnswerKeyDTO.SOURCE_VISION);

    AnswerKeyDTO concise = AnswerKeyPreparationService.concise(source);

    assertThat(concise.referenceAnswer()).isEqualTo("B");
    assertThat(concise.analysis().codePointCount(0, concise.analysis().length()))
        .isLessThanOrEqualTo(521);
  }

  @Test
  @DisplayName("学生可见解析约 500 字时不得提前截断")
  void shouldPreserveAboutFiveHundredCharacterAnalysis() {
    String analysis = "先判断集合关系，再逐步说明正确选项成立的原因，并指出典型错误选项的问题。".repeat(14);
    AnswerKeyDTO source = new AnswerKeyDTO(
        0, "A", Map.of("A", true), analysis, List.of(),
        null, null, false, 90, AnswerKeyDTO.SOURCE_VISION);

    AnswerKeyDTO concise = AnswerKeyPreparationService.concise(source);

    assertThat(concise.analysis().length()).isGreaterThanOrEqualTo(450);
    assertThat(concise.analysis().length()).isLessThanOrEqualTo(520);
    assertThat(concise.analysis()).doesNotEndWith("…");
  }

  @Test
  @DisplayName("单题视觉调用返回错位题号时，映射回本批题目索引")
  void shouldRemapSingleVisualAnswerToExpectedQuestionIndex() {
    AnswerKeyPreparationService.AnswerBatchDTO result =
        new AnswerKeyPreparationService.AnswerBatchDTO(List.of(
            new AnswerKeyPreparationService.GeneratedAnswerDTO(
                2, "B", List.of("B"), null, false, 95)
        ));

    Map<Integer, AnswerKeyPreparationService.GeneratedAnswerDTO> selected =
        AnswerKeyPreparationService.selectAnswers(List.of(1), result);

    assertThat(selected).containsOnlyKeys(1);
    assertThat(selected.get(1).questionIndex()).isEqualTo(1);
    assertThat(selected.get(1).referenceAnswer()).isEqualTo("B");
  }

  @Test
  @DisplayName("选择题以 selectedOptions 为准并派生选项正误")
  void shouldDeriveOptionCorrectnessFromSelectedOptions() {
    String answer = AnswerKeyPreparationService.selectedOptionsAnswer(List.of("c", "A"));

    assertThat(answer).isEqualTo("AC");
    assertThat(AnswerKeyPreparationService.correctnessOf(answer))
        .containsEntry("A", true)
        .containsEntry("B", false)
        .containsEntry("C", true)
        .containsEntry("D", false);
    assertThat(AnswerKeyPreparationService.selectedOptionsAnswer(List.of())).isNull();
  }

  @Test
  @DisplayName("圈号选项的 selectedOptions 与位置字母都能归一到实际选项键")
  void shouldNormalizeCircledChoiceAnswers() {
    InterviewQuestionDTO circled = question(
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(
            new QuestionOption("①", "It is native to lowlands and marshes."),
            new QuestionOption("②", "It is more closely related to the mule deer."),
            new QuestionOption("③", "It has replaced the black-tailed deer."),
            new QuestionOption("④", "It no longer lives in a particular habitat.")
        )
    );

    AnswerKeyPreparationService.GeneratedAnswerDTO selected =
        new AnswerKeyPreparationService.GeneratedAnswerDTO(
            0, "④", List.of("④"), null, false, 95);
    AnswerKeyPreparationService.GeneratedAnswerDTO positional =
        new AnswerKeyPreparationService.GeneratedAnswerDTO(
            0, "D", List.of("D"), null, false, 95);

    assertThat(AnswerKeyPreparationService.candidateAnswer(circled, selected))
        .isEqualTo("④");
    assertThat(AnswerKeyPreparationService.candidateAnswer(circled, positional))
        .isEqualTo("④");
  }

  @Test
  @DisplayName("解析阶段按 questionIndex 独立合并，错位单题可回映")
  void shouldSelectGeneratedAnalysis() {
    AnswerKeyPreparationService.AnalysisBatchDTO result =
        new AnswerKeyPreparationService.AnalysisBatchDTO(List.of(
            new AnswerKeyPreparationService.GeneratedAnalysisDTO(
                7, "解析内容", List.of("考点"), "评分规则")
        ));

    Map<Integer, AnswerKeyPreparationService.GeneratedAnalysisDTO> selected =
        AnswerKeyPreparationService.selectAnalyses(List.of(3), result);

    assertThat(selected).containsOnlyKeys(3);
    assertThat(selected.get(3).analysis()).isEqualTo("解析内容");
    assertThat(selected.get(3).keyPoints()).containsExactly("考点");
  }

  @Test
  @DisplayName("解析按配置批量生成，避免单次 JSON 过长或调用次数过多")
  void shouldSplitAnalysisIntoSmallBatches() {
    List<List<Integer>> batches = AnswerKeyPreparationService.analysisBatches(
        List.of(1, 2, 3, 4, 5, 6, 7, 8), 3);

    assertThat(batches).hasSize(3);
    assertThat(batches).extracting(List::size).containsExactly(3, 3, 2);
  }

  @Test
  @DisplayName("选择题低置信度但 referenceAnswer 有效时仍保留候选答案")
  void shouldKeepLowConfidenceReferenceAnswerWhenSelectedOptionsMissing() {
    InterviewQuestionDTO question = question(
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(
            new QuestionOption("A", null, "a"),
            new QuestionOption("B", null, "b"),
            new QuestionOption("C", null, "c"),
            new QuestionOption("D", null, "d")
        )
    );
    AnswerKeyPreparationService.GeneratedAnswerDTO generated =
        new AnswerKeyPreparationService.GeneratedAnswerDTO(
            0, "C", List.of(), "三个图案顺时针相邻", false, 18);

    assertThat(AnswerKeyPreparationService.hasUsableGeneratedAnswer(question, generated))
        .isTrue();
    assertThat(AnswerKeyPreparationService.candidateAnswer(question, generated))
        .isEqualTo("C");
  }

  @Test
  @DisplayName("选项字母与计算结果冲突时优先采用计算结果对应的选项")
  void shouldPreferDerivedResultOverConflictingSelectedOption() {
    InterviewQuestionDTO question = question(
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(
            new QuestionOption("A", "(1/4,0)"),
            new QuestionOption("B", "(0,1/2)"),
            new QuestionOption("C", "(0,1/4)"),
            new QuestionOption("D", "(1/2,0)")
        )
    );
    AnswerKeyPreparationService.GeneratedAnswerDTO generated =
        new AnswerKeyPreparationService.GeneratedAnswerDTO(
            0, "C", List.of("C"), "(0,1/2)", false, 95, "(0,1/2)");

    assertThat(AnswerKeyPreparationService.candidateAnswer(question, generated))
        .isEqualTo("B");
  }

  @Test
  @DisplayName("模型仅返回图形摘要但未返回答案时不能冒充候选答案")
  void shouldRequireReferenceAnswerInsteadOfFigureSummary() {
    InterviewQuestionDTO question = question(InterviewQuestionDTO.TYPE_FILL_BLANK, null);
    AnswerKeyPreparationService.GeneratedAnswerDTO generated =
        new AnswerKeyPreparationService.GeneratedAnswerDTO(
            0, "", List.of(), "图(1)在上排第二个方格补画；图(2)在下排第三个方格补画",
            true, 15);

    assertThat(AnswerKeyPreparationService.hasUsableGeneratedAnswer(question, generated))
        .isFalse();
    assertThat(AnswerKeyPreparationService.candidateAnswer(question, generated)).isNull();
  }

  private InterviewQuestionDTO question(
      String questionType,
      List<QuestionOption> options
  ) {
    return new InterviewQuestionDTO(
        0, "4 如图，在方格中补全正方体展开图", "math", "几何", null,
        null, null, null, false, null, null, List.of(), null, null, 4,
        questionType, options, null, 1, 1, "图(1) 图(2)", null, null, true);
  }

}
