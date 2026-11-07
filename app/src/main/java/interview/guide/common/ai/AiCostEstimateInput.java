package interview.guide.common.ai;

/**
 * 一次业务操作的成本估算入参。估算不到时传 null，由估算器回退默认值。
 */
public record AiCostEstimateInput(
    Long pageCount,
    Long questionCount,
    Long contextTokens,
    Long chunkCount,
    Long outputTokens
) {
}
