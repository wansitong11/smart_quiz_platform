package interview.guide.modules.quiz.model;

/**
 * 错题生命周期状态。
 */
public enum WrongQuestionStatus {
  PENDING("待复习"),
  MASTERED("已掌握"),
  ARCHIVED("已移除");

  private final String label;

  WrongQuestionStatus(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
