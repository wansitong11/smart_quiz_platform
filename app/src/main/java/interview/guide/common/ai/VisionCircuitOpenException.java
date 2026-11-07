package interview.guide.common.ai;

/**
 * vision provider 熔断中；调用方应退避后重投整卷。
 */
public class VisionCircuitOpenException extends VisionRequestRejectedException {

  public VisionCircuitOpenException() {
    super("AI 识图服务暂时熔断，请稍后重试");
  }
}
