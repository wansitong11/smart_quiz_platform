package interview.guide.common.exception;

/**
 * 月度 AI 成本额度拦截。前端按错误码识别后展示「本月额度已用完」。
 */
public class AiBudgetExceededException extends BusinessException {

  public AiBudgetExceededException(String message) {
    super(ErrorCode.AI_BUDGET_EXCEEDED, message);
  }
}
