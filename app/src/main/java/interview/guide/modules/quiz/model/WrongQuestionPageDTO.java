package interview.guide.modules.quiz.model;

import java.util.List;

/**
 * 错题本分页结果与筛选聚合。
 */
public record WrongQuestionPageDTO(
    List<WrongQuestionDTO> items,
    long total,
    int page,
    int size,
    List<CountItem> statusCounts,
    List<CountItem> subjectCounts
) {

  public record CountItem(String key, String label, long count) {
  }
}
