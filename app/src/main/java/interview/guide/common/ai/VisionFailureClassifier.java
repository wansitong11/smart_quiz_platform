package interview.guide.common.ai;

import com.openai.errors.OpenAIServiceException;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import java.util.Objects;

/**
 * 视觉调用异常分类器。
 *
 * <p>只依据异常类型和 HTTP 状态码判断，不读取 provider 原始 message，避免把用户输入
 * 或上游响应内容当成业务规则，也避免日志泄露。</p>
 */
public final class VisionFailureClassifier {

  private static final int HTTP_REQUEST_TIMEOUT = 408;
  private static final int HTTP_TOO_MANY_REQUESTS = 429;

  private VisionFailureClassifier() {
  }

  public static VisionFailureClass classify(Throwable error) {
    if (findCause(error, VisionRequestRejectedException.class) != null) {
      return VisionFailureClass.RATE_LIMITED;
    }
    BusinessException businessError = findCause(error, BusinessException.class);
    if (businessError != null && Objects.equals(
        businessError.getCode(), ErrorCode.AI_RATE_LIMIT_EXCEEDED.getCode())) {
      return VisionFailureClass.RATE_LIMITED;
    }
    OpenAIServiceException serviceError = findCause(error, OpenAIServiceException.class);
    if (serviceError != null) {
      int statusCode = serviceError.statusCode();
      if (statusCode == HTTP_TOO_MANY_REQUESTS) {
        return VisionFailureClass.RATE_LIMITED;
      }
      if (statusCode == HTTP_REQUEST_TIMEOUT || statusCode >= 500) {
        return VisionFailureClass.TRANSIENT_TIMEOUT;
      }
    }
    if (findCause(error, UnexpectedEndOfInputException.class) != null) {
      return VisionFailureClass.OUTPUT_TRUNCATED;
    }
    if (findCause(error, JacksonException.class) != null) {
      return VisionFailureClass.INVALID_OUTPUT;
    }
    if (StructuredOutputInvoker.isTransportFailure(error)) {
      return VisionFailureClass.TRANSIENT_TIMEOUT;
    }
    return VisionFailureClass.TERMINAL;
  }

  public static boolean canRetryAfterFailure(VisionFailureClass failureClass) {
    return failureClass == VisionFailureClass.RATE_LIMITED
        || failureClass == VisionFailureClass.INVALID_OUTPUT
        || failureClass == VisionFailureClass.OUTPUT_TRUNCATED
        || failureClass == VisionFailureClass.INCOMPLETE_CONTENT;
  }

  private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
    Throwable current = error;
    while (current != null) {
      if (type.isInstance(current)) {
        return type.cast(current);
      }
      Throwable cause = current.getCause();
      if (cause == current) {
        break;
      }
      current = cause;
    }
    return null;
  }
}
