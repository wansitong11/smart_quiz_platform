package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;
import java.util.List;

import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;

public record KnowledgeBaseQuestionDTO(
    Long id,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    String skillId,
    String difficulty,
    String type,
    String category,
    String question,
    String topicSummary,
    String referenceAnswer,
    List<String> keyPoints,
    String scoringRubric,
    List<KnowledgeBaseQuestionFollowUpDTO> followUps,
    // ===== 考卷题字段（与模拟测验同构）=====
    String questionType,
    List<QuestionOption> options,
    Integer fullScore,
    String analysis,
    String sourceContext,
    KnowledgeBaseQuestionStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
