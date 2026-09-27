package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试评估报告
 */
public record InterviewReportDTO(
    String sessionId,
    int totalQuestions,
    int overallScore,                          // 得分率 (0-100)
    int totalFullScore,                        // 卷面总分（各题分值之和；无分值信息的场景为 0）
    int paperScore,                            // 卷面得分（按各题分值折算求和；无分值信息的场景为 0）
    List<CategoryScore> categoryScores,        // 各类别得分
    List<QuestionEvaluation> questionDetails,  // 每题详情
    String overallFeedback,                    // 总体评价
    List<String> strengths,                    // 优势
    List<String> improvements,                 // 改进建议
    List<ReferenceAnswer> referenceAnswers     // 参考答案
) {
    /**
     * 类别得分
     */
    public record CategoryScore(
        String category,
        int score,
        int questionCount
    ) {}
    
    /**
     * 问题评估详情
     */
    public record QuestionEvaluation(
        int questionIndex,
        String question,
        String category,
        String userAnswer,
        int score,             // 该题得分率 (0-100)
        String feedback,
        Integer fullScore,     // 该题分值（试卷场景；自动出题为 null）
        Integer questionScore  // 该题卷面得分（得分率 × 分值折算）
    ) {}
    
    /**
     * 参考答案
     */
    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {}
}
