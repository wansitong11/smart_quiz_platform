package interview.guide.modules.quiz.model;

/**
 * 错题本列表筛选条件。subjectCode 与旧接口的 skillId 同义。
 */
public record WrongQuestionFilter(
    String examCode,
    String subjectCode,
    String knowledgePointKey,
    String questionType,
    WrongQuestionStatus status,
    String keyword
) {
}
