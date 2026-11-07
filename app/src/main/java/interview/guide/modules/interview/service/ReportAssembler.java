package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.StudentFacingFeedbackSanitizer;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.CategoryScore;
import interview.guide.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import interview.guide.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
import interview.guide.modules.interview.model.InterviewReportDTO.ScoreStatus;
import interview.guide.modules.interview.service.VisualQuestionEvaluationService.VisualGradeDTO;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 批改报告组装器：把客观题、文本主观题、视觉主观题三类结果合并成统一的 InterviewReportDTO，
 * 并完成分值折算、文案生成与反馈裁剪。全部为无状态纯函数，不触发模型调用。
 */
public final class ReportAssembler {

  private static final int MAX_FEEDBACK_CHARS = 900;

  private ReportAssembler() {
  }

  /**
   * 合并客观题判分与模型批改结果，产出统一报告。
   */
  public static InterviewReportDTO mergeReports(String sessionId,
                                                 List<InterviewQuestionDTO> questions,
                                                 Map<Integer, ObjectiveAnswerGrader.Grade> objectiveGrades,
                                                 Map<Integer, VisualGradeDTO> visualGrades,
                                                 List<InterviewQuestionDTO> pendingAnswerKeyQuestions,
                                                 EvaluationReport llmReport) {
    Map<Integer, InterviewQuestionDTO> questionByIndex = questions.stream()
        .collect(java.util.stream.Collectors.toMap(
            InterviewQuestionDTO::questionIndex, question -> question, (left, right) -> left));
    Map<Integer, QuestionEvaluation> details = new LinkedHashMap<>();
    if (llmReport != null) {
      for (EvaluationReport.QuestionEvaluation qe : llmReport.questionDetails()) {
        InterviewQuestionDTO question = questionByIndex.get(qe.questionIndex());
        details.put(qe.questionIndex(), new QuestionEvaluation(
            qe.questionIndex(), qe.question(), qe.category(), qe.userAnswer(),
            qe.score(), limitFeedback(qe.feedback()), null, null, ScoreStatus.TEXT_MODEL,
            isLowConfidence(question), confidenceOf(question), lowConfidenceMessage(question)));
      }
    }
    for (Map.Entry<Integer, VisualGradeDTO> entry : visualGrades.entrySet()) {
      InterviewQuestionDTO question = questionByIndex.get(entry.getKey());
      VisualGradeDTO grade = entry.getValue();
      ScoreStatus status = grade.needsManualReview()
          ? ScoreStatus.MANUAL_REVIEW : ScoreStatus.VISION_MODEL;
      boolean lowConfidence = isLowConfidence(question)
          || grade.confidence() != null && grade.confidence() < 60;
      String keyMessage = lowConfidenceMessage(question);
      String message = grade.needsManualReview()
          ? "需人工复核"
          : lowConfidence
              ? keyMessage != null
                  ? keyMessage : "图形批改置信度较低，当前判分仅供参考。"
              : null;
      details.put(entry.getKey(), new QuestionEvaluation(
          entry.getKey(), question.question(), question.category(), question.userAnswer(),
          grade.score(), limitFeedback(grade.feedback()), null, null, status, lowConfidence,
          confidenceOf(question), message));
    }
    for (InterviewQuestionDTO question : pendingAnswerKeyQuestions) {
      details.put(question.questionIndex(), new QuestionEvaluation(
          question.questionIndex(), question.question(), question.category(), question.userAnswer(),
          0, "本题答案键未成功生成或置信度过低，未自动判分，请重新切题生成答案键或人工复核。",
          null, null, ScoreStatus.MANUAL_REVIEW, true, confidenceOf(question),
          "答案键不可用，当前未自动判分"));
    }
    for (InterviewQuestionDTO question : questions) {
      ObjectiveAnswerGrader.Grade grade = objectiveGrades.get(question.questionIndex());
      if (grade == null) {
        continue;
      }
      details.put(question.questionIndex(), new QuestionEvaluation(
          question.questionIndex(), question.question(), question.category(), question.userAnswer(),
          grade.scoreRate(), limitFeedback(grade.feedback()), null, null, ScoreStatus.AUTO,
          isLowConfidence(question), confidenceOf(question), lowConfidenceMessage(question)));
    }
    // 模型漏评的题不能伪装成正常 0 分，标记为待人工复核。
    for (InterviewQuestionDTO question : questions) {
      details.putIfAbsent(question.questionIndex(), new QuestionEvaluation(
          question.questionIndex(), question.question(), question.category(), question.userAnswer(),
          0, "未获得批改结果，请人工复核或重新批改。", null, null,
          ScoreStatus.MANUAL_REVIEW, isLowConfidence(question), confidenceOf(question),
          "需人工复核"));
    }

    List<QuestionEvaluation> ordered = details.values().stream()
        .sorted(Comparator.comparingInt(QuestionEvaluation::questionIndex))
        .toList();

    List<ReferenceAnswer> references = buildReferenceAnswers(questions, llmReport);
    List<CategoryScore> categoryScores = buildCategoryScores(questions, ordered);
    int overallScore = weightedScoreRate(questions, ordered);
    boolean objectiveOnly = llmReport == null && visualGrades.isEmpty()
        && pendingAnswerKeyQuestions.isEmpty();

    String overallFeedback;
    List<String> strengths;
    List<String> improvements;
    if (objectiveOnly) {
      overallFeedback = buildObjectiveFeedback(ordered, overallScore);
      strengths = buildCategoryHighlights(categoryScores, true);
      improvements = buildCategoryHighlights(categoryScores, false);
    } else if (llmReport != null) {
      overallFeedback = llmReport.overallFeedback();
      strengths = llmReport.strengths();
      improvements = llmReport.improvements();
    } else {
      overallFeedback = pendingAnswerKeyQuestions.isEmpty()
          ? "本卷包含图形主观题，已结合原图完成批改；标为需人工复核的题目未计入自动总分。"
          : "部分题目的答案键未成功生成，系统没有用低质量答案强行判分；"
              + "相关题目已标为待人工复核且未计入自动总分。";
      strengths = buildCategoryHighlights(categoryScores, true);
      improvements = buildCategoryHighlights(categoryScores, false);
    }

    return new InterviewReportDTO(
        sessionId,
        questions.size(),
        overallScore,
        0,
        0,
        categoryScores,
        ordered,
        overallFeedback,
        strengths == null ? List.of() : strengths,
        improvements == null ? List.of() : improvements,
        references
    );
  }

  /**
   * 补全每题的分值与卷面分，并把参考答案回填为切题时落库的版本。
   */
  public static InterviewReportDTO withQuestionReferences(InterviewReportDTO report,
                                                           List<InterviewQuestionDTO> questions) {
    List<ReferenceAnswer> references = report.referenceAnswers().stream()
        .map(reference -> {
          InterviewQuestionDTO question = questions.stream()
              .filter(item -> item.questionIndex() == reference.questionIndex())
              .findFirst()
              .orElse(null);
          if (question == null || question.referenceAnswer() == null
              || question.referenceAnswer().isBlank()) {
            return reference;
          }
          return new ReferenceAnswer(
              reference.questionIndex(),
              reference.question(),
              question.referenceAnswer(),
              question.keyPoints() != null ? question.keyPoints() : List.of()
          );
        })
        .toList();
    return new InterviewReportDTO(
        report.sessionId(),
        report.totalQuestions(),
        report.overallScore(),
        sumFullScore(questions),
        sumPaperScore(report, questions),
        report.categoryScores(),
        withFullScores(report.questionDetails(), questions),
        report.overallFeedback(),
        report.strengths(),
        report.improvements(),
        references
    );
  }

  /**
   * 参考答案：优先用模型给出的深度解析，客观题用切题时落库的参考答案与要点。
   */
  private static List<ReferenceAnswer> buildReferenceAnswers(
      List<InterviewQuestionDTO> questions, EvaluationReport llmReport) {
    Map<Integer, ReferenceAnswer> byIndex = new LinkedHashMap<>();
    if (llmReport != null) {
      for (EvaluationReport.ReferenceAnswer ra : llmReport.referenceAnswers()) {
        byIndex.put(ra.questionIndex(), new ReferenceAnswer(
            ra.questionIndex(), ra.question(), ra.referenceAnswer(), ra.keyPoints()));
      }
    }
    for (InterviewQuestionDTO question : questions) {
      if (!question.hasReferenceMaterial()) {
        continue;
      }
      byIndex.putIfAbsent(question.questionIndex(), new ReferenceAnswer(
          question.questionIndex(),
          question.question(),
          hasText(question.referenceAnswer()) ? question.referenceAnswer() : null,
          question.keyPoints() != null ? question.keyPoints() : List.of()));
    }
    return byIndex.values().stream()
        .sorted(Comparator.comparingInt(ReferenceAnswer::questionIndex))
        .toList();
  }

  /**
   * 按知识板块汇总平均得分率（客观题与主观题一并统计）。
   */
  private static List<CategoryScore> buildCategoryScores(List<InterviewQuestionDTO> questions,
                                                          List<QuestionEvaluation> details) {
    Map<Integer, String> categoryOf = new LinkedHashMap<>();
    for (InterviewQuestionDTO question : questions) {
      categoryOf.put(question.questionIndex(),
          hasText(question.category()) ? question.category() : "未分类");
    }
    Map<String, int[]> stats = new LinkedHashMap<>();
    for (QuestionEvaluation detail : details) {
      if (detail.scoreStatus() == ScoreStatus.MANUAL_REVIEW) {
        continue;
      }
      String category = categoryOf.getOrDefault(detail.questionIndex(), "未分类");
      int[] acc = stats.computeIfAbsent(category, key -> new int[2]);
      acc[0] += detail.score();
      acc[1] += 1;
    }
    return stats.entrySet().stream()
        .map(entry -> new CategoryScore(entry.getKey(),
            entry.getValue()[1] == 0 ? 0 : Math.round((float) entry.getValue()[0] / entry.getValue()[1]),
            entry.getValue()[1]))
        .toList();
  }

  /**
   * 综合得分率：有分值时按分值加权，无分值时取算术平均。
   */
  private static int weightedScoreRate(List<InterviewQuestionDTO> questions,
                                        List<QuestionEvaluation> details) {
    Map<Integer, Integer> fullScoreOf = new LinkedHashMap<>();
    for (InterviewQuestionDTO question : questions) {
      Integer fullScore = question.fullScore();
      fullScoreOf.put(question.questionIndex(), fullScore != null && fullScore > 0 ? fullScore : 0);
    }
    boolean hasFullScore = fullScoreOf.values().stream().anyMatch(score -> score > 0);
    double weighted = 0;
    double weight = 0;
    for (QuestionEvaluation detail : details) {
      if (detail.scoreStatus() == ScoreStatus.MANUAL_REVIEW) {
        continue;
      }
      int fullScore = fullScoreOf.getOrDefault(detail.questionIndex(), 0);
      double w = hasFullScore ? (fullScore > 0 ? fullScore : 0) : 1;
      if (w == 0) {
        continue;
      }
      weighted += detail.score() * w;
      weight += w;
    }
    return weight == 0 ? 0 : (int) Math.round(weighted / weight);
  }

  private static String buildObjectiveFeedback(List<QuestionEvaluation> details, int overallScore) {
    long fullMark = details.stream()
        .filter(detail -> detail.scoreStatus() != ScoreStatus.MANUAL_REVIEW)
        .filter(detail -> detail.score() >= 100)
        .count();
    return "本次共 " + details.size() + " 道客观题，全部由系统按标准答案判分：全对 "
        + fullMark + " 题，整体得分率 " + overallScore + "%。";
  }

  private static List<String> buildCategoryHighlights(List<CategoryScore> categoryScores, boolean positive) {
    return categoryScores.stream()
        .filter(score -> positive ? score.score() >= 80 : score.score() < 60)
        .sorted((a, b) -> positive
            ? Integer.compare(b.score(), a.score())
            : Integer.compare(a.score(), b.score()))
        .limit(3)
        .map(score -> (positive ? "掌握较好：" : "需要加强：") + score.category()
            + "（" + score.score() + "%，共 " + score.questionCount() + " 题）")
        .toList();
  }

  /**
   * 卷面总分：各题分值之和。普通自动出题场景题目没有分值，返回 0。
   */
  private static int sumFullScore(List<InterviewQuestionDTO> questions) {
    return questions.stream()
        .map(InterviewQuestionDTO::fullScore)
        .filter(Objects::nonNull)
        .filter(score -> score > 0)
        .mapToInt(Integer::intValue)
        .sum();
  }

  /**
   * 卷面得分：每题的得分率按该题分值折算后求和（得分率 × 分值 / 100，四舍五入）。
   */
  private static int sumPaperScore(InterviewReportDTO report, List<InterviewQuestionDTO> questions) {
    int total = 0;
    for (QuestionEvaluation evaluation : report.questionDetails()) {
      if (evaluation.scoreStatus() == ScoreStatus.MANUAL_REVIEW) {
        continue;
      }
      total += paperScoreOf(evaluation.score(), fullScoreOf(questions, evaluation.questionIndex()));
    }
    return total;
  }

  /**
   * 给每题补上分值与折算后的卷面得分。
   */
  private static List<QuestionEvaluation> withFullScores(
      List<QuestionEvaluation> evaluations,
      List<InterviewQuestionDTO> questions) {
    return evaluations.stream()
        .map(evaluation -> {
          Integer fullScore = fullScoreOf(questions, evaluation.questionIndex());
          return new QuestionEvaluation(
              evaluation.questionIndex(),
              evaluation.question(),
              evaluation.category(),
              evaluation.userAnswer(),
              evaluation.score(),
              evaluation.feedback(),
              fullScore,
              evaluation.scoreStatus() == ScoreStatus.MANUAL_REVIEW || fullScore == null
                  ? null : paperScoreOf(evaluation.score(), fullScore),
              evaluation.scoreStatus(),
              evaluation.lowConfidence(),
              evaluation.answerKeyConfidence(),
              evaluation.scoreMessage()
          );
        })
        .toList();
  }

  private static Integer fullScoreOf(List<InterviewQuestionDTO> questions, int questionIndex) {
    return questions.stream()
        .filter(item -> item.questionIndex() == questionIndex)
        .map(InterviewQuestionDTO::fullScore)
        .filter(score -> score != null && score > 0)
        .findFirst()
        .orElse(null);
  }

  private static int paperScoreOf(int scoreRate, Integer fullScore) {
    if (fullScore == null || fullScore <= 0) {
      return 0;
    }
    return (int) Math.round(Math.max(0, Math.min(100, scoreRate)) * fullScore / 100.0);
  }

  private static boolean isLowConfidence(InterviewQuestionDTO question) {
    return question != null && question.hasLowConfidenceAnswerKey();
  }

  private static Integer confidenceOf(InterviewQuestionDTO question) {
    return question == null ? null : question.answerKeyConfidence();
  }

  private static String lowConfidenceMessage(InterviewQuestionDTO question) {
    if (!isLowConfidence(question)) {
      return null;
    }
    return "本题答案依据由 AI 生成，置信度较低（"
        + question.answerKeyConfidence()
        + "%），当前判分仅供参考，建议结合原题自行复核。";
  }

  static String limitFeedback(String feedback) {
    if (feedback == null || feedback.isBlank()) {
      return feedback;
    }
    String normalized = StudentFacingFeedbackSanitizer.sanitize(feedback)
        .replaceAll("\\s+", " ").trim();
    if (normalized.length() <= MAX_FEEDBACK_CHARS) {
      return normalized;
    }
    int end = MAX_FEEDBACK_CHARS;
    for (int i = MAX_FEEDBACK_CHARS - 1; i >= MAX_FEEDBACK_CHARS / 2; i--) {
      char ch = normalized.charAt(i);
      if (ch == '。' || ch == '！' || ch == '？' || ch == '；') {
        end = i + 1;
        break;
      }
    }
    return normalized.substring(0, end).stripTrailing() + "…";
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
