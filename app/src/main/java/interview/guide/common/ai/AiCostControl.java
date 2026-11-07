package interview.guide.common.ai;

/**
 * 预算预占与结算控制插口。实现方放在业务模块，common 只依赖该接口，
 * 避免 common -> modules 的反向依赖。
 */
public interface AiCostControl {

  void reserveOrThrow(String operationId, Long userId, AiFeature feature, AiCostEstimateInput input);

  void finalize(String operationId);
}
