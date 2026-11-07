package interview.guide.modules.quiz.model;

import interview.guide.modules.interview.model.InterviewQuestionDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 错题本列表条目。只包含学生可见内容，不暴露内部答案键。
 */
public record WrongQuestionDTO(
    String questionKey,
    String skillId,
    String examCode,
    String examName,
    String subjectName,
    String knowledgePointKey,
    String knowledgePointName,
    String questionType,
    String typeLabel,
    String stem,
    List<InterviewQuestionDTO.QuestionOption> options,
    Integer fullScore,
    List<WrongQuestionSnapshot.MaterialBlock> materials,
    Long paperId,
    List<String> assetIds,
    String userAnswer,
    String referenceAnswer,
    String analysis,
    Integer lastScoreRate,
    int correctCount,
    int wrongCount,
    WrongQuestionStatus status,
    String statusLabel,
    LocalDateTime lastWrongAt,
    LocalDateTime lastCorrectAt,
    LocalDateTime masteredAt,
    LocalDateTime archivedAt
) {
}
