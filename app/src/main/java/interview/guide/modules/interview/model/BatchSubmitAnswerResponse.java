package interview.guide.modules.interview.model;

/**
 * 整卷交卷响应。
 *
 * @param sessionId      会话 ID
 * @param answeredCount  本次提交的题目数
 * @param totalQuestions 整卷题目总数
 * @param blankCount     作答为空的题目数（按 0 分处理）
 * @param status         会话状态，交卷后为 COMPLETED
 * @param evaluateStatus 批改任务状态，交卷后为 PENDING
 */
public record BatchSubmitAnswerResponse(
    String sessionId,
    int answeredCount,
    int totalQuestions,
    int blankCount,
    String status,
    String evaluateStatus
) {}
