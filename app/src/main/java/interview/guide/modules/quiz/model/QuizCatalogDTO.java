package interview.guide.modules.quiz.model;

import java.util.List;

/**
 * 测验目录：考试分组 → 科目 → 考点 → 题型与默认分。
 */
public record QuizCatalogDTO(List<Exam> exams) {

  public record Exam(
      String code,
      String name,
      String description,
      Display display,
      boolean customBucket,
      int sortOrder,
      List<Subject> subjects
  ) {
  }

  public record Display(String icon, String gradient, String iconBg, String iconColor) {
  }

  public record Subject(
      String code,
      String name,
      String description,
      String examCode,
      boolean custom,
      List<KnowledgePoint> knowledgePoints,
      List<QuestionType> questionTypes,
      Integer suggestedMinutes,
      String clonedFromCode,
      String outlineText
  ) {
  }

  public record KnowledgePoint(String code, String label) {
  }

  public record QuestionType(String type, int defaultScore, int weight) {
  }
}
