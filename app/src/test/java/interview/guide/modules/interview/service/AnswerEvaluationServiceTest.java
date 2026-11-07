package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.InterviewEvaluationProperties;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerEvaluationServiceTest {

  @Mock
  private UnifiedEvaluationService unifiedEvaluationService;

  @Mock
  private InterviewPersistenceService persistenceService;

  @Mock
  private InterviewSkillService skillService;

  @Mock
  private VisualQuestionEvaluationService visualQuestionEvaluationService;

  @Test
  @DisplayName("题目自带评分依据时不再拼接 Skill 参考基线")
  void shouldSkipSkillReferenceWhenQuestionsHaveReferences() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService,
        persistenceService,
        skillService,
        new ObjectiveAnswerGrader(new InterviewEvaluationProperties()),
        visualQuestionEvaluationService
    );
    InterviewQuestionDTO question = InterviewQuestionDTO.fromQuestionBank(
        0,
        "什么是索引下推？",
        "MYSQL",
        "MySQL",
        "索引优化",
        "参考答案",
        List.of("评分要点"),
        "评分规则",
        "来源片段"
    );
    when(unifiedEvaluationService.evaluate(
        nullable(ChatClient.class), eq("session1"), any(), eq("简历"), any()
    )).thenReturn(report());

    InterviewReportDTO actual = service.evaluateInterview(null, "session1", "简历", List.of(question));

    ArgumentCaptor<String> referenceCaptor = ArgumentCaptor.forClass(String.class);
    verify(unifiedEvaluationService).evaluate(
        nullable(ChatClient.class), eq("session1"), any(), eq("简历"), referenceCaptor.capture()
    );
    verify(skillService, never()).buildEvaluationReferenceSectionSafe(any());
    verify(persistenceService, never()).findBySessionId(any());
    assertThat(referenceCaptor.getValue())
        .contains("参考答案")
        .contains("评分要点")
        .contains("评分规则")
        .doesNotContain("来源片段");
    assertThat(actual.referenceAnswers().getFirst().referenceAnswer()).isEqualTo("参考答案");
  }

  @Test
  @DisplayName("低置信度答案键正常计分并写入批改提示")
  void shouldMarkLowConfidenceAnswerKeyInReport() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService,
        persistenceService,
        skillService,
        new ObjectiveAnswerGrader(new InterviewEvaluationProperties()),
        visualQuestionEvaluationService
    );
    InterviewQuestionDTO question = InterviewQuestionDTO.fromQuestionBank(
        0, "什么是索引下推？", "MYSQL", "MySQL", "索引优化",
        "参考答案", List.of("评分要点"), "评分规则", "来源片段")
        .withAnswer("回答")
        .withAnswerKey(new AnswerKeyDTO(
            0, "参考答案", Map.of(), "模型解析", List.of("评分要点"),
            "评分规则", null, false, 55, AnswerKeyDTO.SOURCE_TEXT));
    when(unifiedEvaluationService.evaluate(
        nullable(ChatClient.class), eq("session1"), any(), eq("简历"), any()
    )).thenReturn(report());

    InterviewReportDTO actual = service.evaluateInterview(
        null, "session1", "简历", List.of(question));

    assertThat(actual.questionDetails()).singleElement().satisfies(detail -> {
      assertThat(detail.lowConfidence()).isTrue();
      assertThat(detail.answerKeyConfidence()).isEqualTo(55);
      assertThat(detail.scoreMessage()).contains("置信度较低");
    });
  }

  @Test
  @DisplayName("客观题答案键不可用时不调用文本模型猜答案")
  void shouldNotGuessObjectiveQuestionWithoutUsableAnswerKey() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService,
        persistenceService,
        skillService,
        new ObjectiveAnswerGrader(new InterviewEvaluationProperties()),
        visualQuestionEvaluationService
    );
    InterviewQuestionDTO question = new InterviewQuestionDTO(
        0, "图示选择题", "math", "几何", null, "C", null, null, false, null,
        "暂缺", List.of(), null, null, 3, InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(new QuestionOption("A", null, "a"), new QuestionOption("B", null, "b"),
            new QuestionOption("C", null, "c"), new QuestionOption("D", null, "d")),
        "暂缺", 1, 1, null, null, 20, false
    ).withAnswerKey(new AnswerKeyDTO(
        0, "暂缺", Map.of(), "暂缺", List.of(), null, null, false, 20,
        AnswerKeyDTO.SOURCE_TEXT));

    InterviewReportDTO actual = service.evaluateInterview(
        null, "session1", "试卷", List.of(question));

    verify(unifiedEvaluationService, never()).evaluate(
        nullable(ChatClient.class), eq("session1"), any(), eq("试卷"), any());
    assertThat(actual.questionDetails()).singleElement().satisfies(detail -> {
      assertThat(detail.scoreStatus()).isEqualTo(InterviewReportDTO.ScoreStatus.MANUAL_REVIEW);
      assertThat(detail.score()).isZero();
      assertThat(detail.scoreMessage()).contains("答案键不可用");
    });
  }

  @Test
  @DisplayName("低置信度但已有答案的客观题仍正常判分并提示")
  void shouldGradeObjectiveQuestionWithLowConfidenceAnswer() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService,
        persistenceService,
        skillService,
        new ObjectiveAnswerGrader(new InterviewEvaluationProperties()),
        visualQuestionEvaluationService
    );
    InterviewQuestionDTO question = new InterviewQuestionDTO(
        0, "图示选择题", "math", "几何", null, "C", null, null, false, null,
        "C", List.of(), null, null, 3, InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(new QuestionOption("A", null), new QuestionOption("B", null),
            new QuestionOption("C", null), new QuestionOption("D", null)),
        "依照图形可得 C。", 1, 1, null, null, 20, false
    ).withAnswer("C").withAnswerKey(new AnswerKeyDTO(
        0, "C", Map.of("C", true), "依照图形可得 C。", List.of(), null,
        null, false, 20, AnswerKeyDTO.SOURCE_TEXT));

    InterviewReportDTO actual = service.evaluateInterview(
        null, "session1", "试卷", List.of(question));

    verify(unifiedEvaluationService, never()).evaluate(
        nullable(ChatClient.class), eq("session1"), any(), eq("试卷"), any());
    assertThat(actual.questionDetails()).singleElement().satisfies(detail -> {
      assertThat(detail.scoreStatus()).isEqualTo(InterviewReportDTO.ScoreStatus.AUTO);
      assertThat(detail.score()).isEqualTo(100);
      assertThat(detail.lowConfidence()).isTrue();
      assertThat(detail.scoreMessage()).contains("置信度较低");
    });
  }

  @Test
  @DisplayName("完整解析评语约 500 字时不得被 320 字上限截断")
  void shouldPreserveLongStudentFacingFeedback() {
    String feedback = "先给出结论，再说明关键步骤、易错点和纠正方法。".repeat(20);

    assertThat(AnswerEvaluationService.limitFeedback(feedback)).isEqualTo(feedback);
  }

  private EvaluationReport report() {
    return new EvaluationReport(
        "session1",
        1,
        80,
        List.of(new EvaluationReport.CategoryScore("MySQL", 80, 1)),
        List.of(new EvaluationReport.QuestionEvaluation(
            0,
            "什么是索引下推？",
            "MySQL",
            "回答",
            80,
            "不错"
        )),
        "整体不错",
        List.of("基础扎实"),
        List.of("继续深入"),
        List.of(new EvaluationReport.ReferenceAnswer(
            0,
            "什么是索引下推？",
            "模型参考",
            List.of("模型要点")
        ))
    );
  }
}
