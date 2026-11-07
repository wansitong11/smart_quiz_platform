package interview.guide.common.ai;

/**
 * 单 provider 准入队列已满或等待超时；调用方应整批退避重投，而不是把业务页标失败。
 */
public class VisionRequestRejectedException extends RuntimeException {

  public VisionRequestRejectedException(String message) {
    super(message);
  }

  public VisionRequestRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
