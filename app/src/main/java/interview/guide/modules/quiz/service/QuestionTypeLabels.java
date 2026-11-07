package interview.guide.modules.quiz.service;

/**
 * 错题本展示用题型中文名，避免前端散落英文枚举。
 */
public final class QuestionTypeLabels {

  private QuestionTypeLabels() {
  }

  public static String labelOf(String questionType) {
    if (questionType == null || questionType.isBlank()) {
      return "未分类";
    }
    return switch (questionType) {
      case "SINGLE_CHOICE" -> "单选题";
      case "MULTI_CHOICE" -> "多选题";
      case "TRUE_FALSE" -> "判断题";
      case "FILL_BLANK" -> "填空题";
      case "SHORT_ANSWER" -> "简答题";
      case "READING" -> "阅读题";
      case "WRITING" -> "写作题";
      case "CASE_ANALYSIS" -> "案例分析";
      case "OTHER" -> "其他";
      default -> "其他";
    };
  }
}
