package interview.guide.modules.quiz.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * 错题重练请求：勾选重练或按筛选范围随机抽题。
 */
public record WrongQuestionRetryRequest(
    WrongQuestionRetryMode mode,
    List<String> questionKeys,
    @Valid WrongQuestionScope scope,
    @Min(1) @Max(50) Integer count
) {

  public record WrongQuestionScope(
      String examCode,
      String subjectCode,
      String knowledgePointKey,
      String questionType,
      WrongQuestionStatus status,
      String keyword
  ) {
  }
}
