package interview.guide.modules.interview.model;

import interview.guide.common.ai.AnswerKeySupport;

import java.util.List;
import java.util.Map;

/**
 * 单题内部答案键，只用于判分和内部维护，不进入普通作答接口。
 */
public record AnswerKeyDTO(
    int questionIndex,
    String referenceAnswer,
    Map<String, Boolean> optionCorrectness,
    String analysis,
    List<String> keyPoints,
    String scoringRubric,
    String figureSummary,
    boolean requiresVisualGrading,
    int confidence,
    String source,
    List<AnswerCandidate> candidates,
    String resolutionStatus,
    Integer consistencyScore,
    String answerMode,
    String fingerprint
) {
  public static final String SOURCE_TEXT = "TEXT";
  public static final String SOURCE_VISION = "VISION";
  public static final String SOURCE_MANUAL = "MANUAL";
  public static final String SOURCE_OFFICIAL = "OFFICIAL";
  public static final String SOURCE_STRUCTURE = "STRUCTURE";

  public static final String ANSWER_MODE_FIXED = "FIXED";
  public static final String ANSWER_MODE_SAMPLE = "SAMPLE";
  public static final String ANSWER_MODE_RUBRIC_ONLY = "RUBRIC_ONLY";
  public static final String ANSWER_MODE_MANUAL_REVIEW = "MANUAL_REVIEW";

  /**
   * 兼容旧调用：不显式携带答案模式与指纹。
   */
  public AnswerKeyDTO(
      int questionIndex,
      String referenceAnswer,
      Map<String, Boolean> optionCorrectness,
      String analysis,
      List<String> keyPoints,
      String scoringRubric,
      String figureSummary,
      boolean requiresVisualGrading,
      int confidence,
      String source,
      List<AnswerCandidate> candidates,
      String resolutionStatus,
      Integer consistencyScore
  ) {
    this(questionIndex, referenceAnswer, optionCorrectness, analysis, keyPoints,
        scoringRubric, figureSummary, requiresVisualGrading, confidence, source,
        candidates, resolutionStatus, consistencyScore, null, null);
  }

  public AnswerKeyDTO(
      int questionIndex,
      String referenceAnswer,
      Map<String, Boolean> optionCorrectness,
      String analysis,
      List<String> keyPoints,
      String scoringRubric,
      String figureSummary,
      boolean requiresVisualGrading,
      int confidence,
      String source
  ) {
    this(questionIndex, referenceAnswer, optionCorrectness, analysis, keyPoints,
        scoringRubric, figureSummary, requiresVisualGrading, confidence, source,
        List.of(), null, null, null, null);
  }

  /**
   * 是否具备任一可用评分依据：确定答案、评分要点或评分规则。
   */
  public boolean hasScoringBasis() {
    return !AnswerKeySupport.isMissing(referenceAnswer)
        || keyPoints != null && !keyPoints.isEmpty()
        || scoringRubric != null && !scoringRubric.isBlank();
  }

  /**
   * 未显式写入 answerMode 时，按现有内容推断。
   */
  public String effectiveAnswerMode() {
    if (answerMode != null && !answerMode.isBlank()) {
      return answerMode;
    }
    if (!AnswerKeySupport.isMissing(referenceAnswer)) {
      return ANSWER_MODE_SAMPLE;
    }
    if (keyPoints != null && !keyPoints.isEmpty()
        || scoringRubric != null && !scoringRubric.isBlank()) {
      return ANSWER_MODE_RUBRIC_ONLY;
    }
    return ANSWER_MODE_MANUAL_REVIEW;
  }

  public record AnswerCandidate(
      String answer,
      Integer confidence,
      String source
  ) {
  }
}
