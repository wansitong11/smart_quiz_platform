package interview.guide.modules.quiz.model;

import java.util.List;

/**
 * 模型产出的试卷结构（结构化输出目标类型）。
 * <p>
 * 与切题链路的区别：切题是"从已有文本里抽题"，这里是"按知识板块从零出题"，
 * 因此多了一层 sections（每个板块可自带一段阅读材料）。
 */
public record GeneratedPaperDTO(
    String paperTitle,
    String subjectKey,
    List<Section> sections
) {

  public record Section(
      String title,
      /** 知识板块 key（对应 skill.meta.yml 的 categories[].key） */
      String knowledgeCategory,
      /** 阅读题 / 完形填空 / 文言文等需要原文的板块必填，其它板块可为空 */
      Material material,
      List<Question> questions
  ) {}

  public record Material(String title, String content) {}

  public record Question(
      /** SINGLE_CHOICE / MULTI_CHOICE / FILL_BLANK / SHORT_ANSWER / READING / WRITING / OTHER */
      String questionType,
      String stem,
      List<Option> options,
      Integer fullScore,
      String referenceAnswer,
      String analysis,
      List<String> knowledgePoints,
      String scoringRubric
  ) {}

  public record Option(String key, String text) {}
}
