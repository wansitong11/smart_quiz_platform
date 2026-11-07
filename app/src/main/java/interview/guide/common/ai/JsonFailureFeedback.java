package interview.guide.common.ai;

/**
 * 从 Jackson 解析异常提取的、可写进下次 Prompt 的最小纠正信息。
 *
 * @param location 解析失败路径，例如 {@code lines[8].bbox[3]}
 * @param expected 期望类型，例如 {@code 数字（double）}
 * @param actual   实际收到的短值，例如 {@code "bbox"}；已截断，不含完整回包
 */
public record JsonFailureFeedback(
    String location,
    String expected,
    String actual
) {
}
