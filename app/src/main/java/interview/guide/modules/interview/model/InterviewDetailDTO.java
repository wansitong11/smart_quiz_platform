package interview.guide.modules.interview.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试详情DTO
 */
public record InterviewDetailDTO(
    Long id,
    String sessionId,
    Integer totalQuestions,
    String status,
    String evaluateStatus,
    String evaluateError,
    Integer overallScore,
    String sourceType,
    Long knowledgeBaseId,
    String overallFeedback,
    LocalDateTime createdAt,
    LocalDateTime completedAt,
    List<Object> questions,
    List<String> strengths,
    List<String> improvements,
    List<Object> referenceAnswers,
    List<AnswerDetailDTO> answers,
    /** 试卷层级树（唯一题目数据源） */
    PaperTree paperTree,
    /** 按小节汇总的得分 */
    List<SectionScoreDTO> sectionScores
) {

    /**
     * 小节维度得分。
     */
    public record SectionScoreDTO(
        String title,
        String subtitle,
        int level,
        int questionCount,
        int fullScore,
        int score,
        int scoreRate
    ) {}

    /**
     * 答案详情DTO
     */
    public record AnswerDetailDTO(
        Integer questionIndex,
        String question,
        String category,
        String userAnswer,
        Integer score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints,
        LocalDateTime answeredAt
    ) {}
}
