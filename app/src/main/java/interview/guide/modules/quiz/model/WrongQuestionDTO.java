package interview.guide.modules.quiz.model;

import interview.guide.modules.interview.model.InterviewQuestionDTO;

import java.time.LocalDateTime;

/**
 * 错题本条目：同一道题多次做错时按题干聚合，wrongCount 记录次数。
 */
public record WrongQuestionDTO(
    String sessionId,
    Integer questionIndex,
    String skillId,
    String category,
    String questionType,
    String stem,
    String userAnswer,
    String referenceAnswer,
    String analysis,
    Integer score,
    Integer fullScore,
    int wrongCount,
    LocalDateTime lastWrongAt,
    /** 原始题目，供「错题重练」直接重建测验 */
    InterviewQuestionDTO question
) {
}
