package interview.guide.common.ai;

/**
 * 视觉调用失败类别。
 *
 * <p>分类只用于调用方的重试与熔断决策，不直接写进学生可见文案。</p>
 */
public enum VisionFailureClass {

  /** 连接、读取超时或 provider 5xx；不应原样重发。 */
  TRANSIENT_TIMEOUT,

  /** provider 限流；可在退避后重试。 */
  RATE_LIMITED,

  /** 模型返回的 JSON 无法解析；可用修复提示重试。 */
  INVALID_OUTPUT,

  /** 模型返回的 JSON 在结束前被截断；只做一次减少输出的定向重试。 */
  OUTPUT_TRUNCATED,

  /** 模型返回合法 JSON，但页面或答案内容不完整；需要定向重抽。 */
  INCOMPLETE_CONTENT,

  /** 密钥、权限、模型配置等确定性错误；立即失败。 */
  TERMINAL
}
