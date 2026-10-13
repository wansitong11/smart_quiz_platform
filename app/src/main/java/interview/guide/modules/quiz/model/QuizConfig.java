package interview.guide.modules.quiz.model;

import java.util.List;

/**
 * 落库的组卷参数快照，用于「再测一次」与学情统计。
 */
public record QuizConfig(
    String skillId,
    String difficulty,
    int questionCount,
    List<String> categories,
    List<String> questionTypes,
    String llmProvider
) {

  public QuizConfigRequest toRequest() {
    return new QuizConfigRequest(skillId, difficulty, questionCount, categories, questionTypes, llmProvider);
  }
}
