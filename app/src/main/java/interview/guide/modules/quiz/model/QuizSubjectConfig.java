package interview.guide.modules.quiz.model;

import java.util.List;

/**
 * 自定义科目配置（序列化进 quiz_subjects.config_json）。
 */
public record QuizSubjectConfig(
    String description,
    List<KnowledgePoint> knowledgePoints,
    List<QuestionType> questionTypes,
    Integer suggestedMinutes,
    String outlineText,
    String clonedFromCode
) {

  public record KnowledgePoint(String code, String label) {
  }

  public record QuestionType(String type, int defaultScore, int weight) {
  }
}
