package interview.guide.modules.quiz.model;

import java.util.List;

/**
 * 组卷使用的统一科目描述：系统预设科目与用户自定义科目在这里归一。
 * <p>
 * 自定义科目的考点、题型与默认分来自落库的 {@code config_json}；系统科目的
 * persona（命题人设）来自 SKILL.md 正文，自定义科目没有 persona，由调用方按
 * 考试名 + 科目名兜底。
 */
public record QuizSubjectSpec(
    String code,
    String name,
    String description,
    String examCode,
    String examName,
    List<Category> categories,
    List<QuestionType> questionTypes,
    Integer suggestedMinutes,
    String outlineText,
    String persona
) {

  public record Category(String key, String label) {
  }

  public record QuestionType(String type, int defaultScore, int weight) {
  }
}
