package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("报告组装漏评兜底")
class ReportAssemblerTest {

  @Test
  @DisplayName("模型漏评的题标为人工复核，不伪装成正常零分")
  void shouldMarkMissingEvaluationAsManualReview() {
    InterviewQuestionDTO first = question(0);
    InterviewQuestionDTO second = question(1);
    EvaluationReport llmReport = new EvaluationReport(
        "session",
        2,
        80,
        List.of(),
        List.of(new EvaluationReport.QuestionEvaluation(
            0, first.question(), first.category(), first.userAnswer(), 80, "答得不错")),
        "整体不错",
        List.of(),
        List.of(),
        List.of());

    InterviewReportDTO report = ReportAssembler.mergeReports(
        "session",
        List.of(first, second),
        Map.of(),
        Map.of(),
        List.of(),
        llmReport);

    InterviewReportDTO.QuestionEvaluation missing = report.questionDetails().stream()
        .filter(item -> item.questionIndex() == 1)
        .findFirst()
        .orElseThrow();
    assertThat(missing.scoreStatus()).isEqualTo(InterviewReportDTO.ScoreStatus.MANUAL_REVIEW);
    assertThat(missing.feedback()).contains("未获得批改结果");
    assertThat(report.overallScore()).isEqualTo(80);
  }

  private InterviewQuestionDTO question(int index) {
    return new InterviewQuestionDTO(
        index,
        "第" + index + "题",
        "chinese",
        "阅读理解",
        null,
        "作答" + index,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        5,
        InterviewQuestionDTO.TYPE_SHORT_ANSWER,
        null,
        null,
        1,
        1,
        null,
        null,
        null,
        false);
  }
}
