package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;

import java.util.List;

/**
 * 面试会话DTO
 */
public record InterviewSessionDTO(
    String sessionId,
    String resumeText,
    int totalQuestions,
    int currentQuestionIndex,
    List<InterviewQuestionDTO> questions,
    SessionStatus status,
    Long knowledgeBaseId,
    String subjectCategory,
    /** 试卷层级树（卷 → 大题 → 小节 → 材料/题目），供作答页按层级渲染 */
    PaperTree paperTree,
    /** 来源试卷 ID：图形选项需要它来取资源；知识库会话为 null */
    Long paperId,
    /** 会话异步出题状态；历史会话为 COMPLETED */
    AsyncTaskStatus questionStatus,
    /** 出题失败原因，仅 FAILED 时有值 */
    String questionError
) {
    /**
     * 兼容原有构造调用：非异步出题链路的会话默认视为已完成。
     */
    public InterviewSessionDTO(String sessionId,
                               String resumeText,
                               int totalQuestions,
                               int currentQuestionIndex,
                               List<InterviewQuestionDTO> questions,
                               SessionStatus status,
                               Long knowledgeBaseId,
                               String subjectCategory,
                               PaperTree paperTree,
                               Long paperId) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions, status,
            knowledgeBaseId, subjectCategory, paperTree, paperId,
            AsyncTaskStatus.COMPLETED, null);
    }

    /** 学生端视图：题目与层级树都不包含任何答案提示。 */
    public InterviewSessionDTO forAttemptView() {
        return new InterviewSessionDTO(
            sessionId,
            resumeText,
            totalQuestions,
            currentQuestionIndex,
            questions == null ? List.of()
                : questions.stream().map(InterviewQuestionDTO::forAttemptView).toList(),
            status,
            knowledgeBaseId,
            subjectCategory,
            paperTree == null ? null : paperTree.forAttemptView(),
            paperId,
            questionStatus,
            questionError
        );
    }

    public enum SessionStatus {
        CREATED,      // 会话已创建
        IN_PROGRESS,  // 面试进行中
        COMPLETED,    // 面试已完成
        EVALUATED     // 已生成评估报告
    }
}
