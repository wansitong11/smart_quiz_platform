package interview.guide.common.ai;

import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 判断一个失败是否值得重试。
 *
 * <p>Stream 消费者不能把所有 {@link BusinessException} 都当确定性失败：
 * 限流、超时、连接中断本质上是瞬态的，重试会变好；参数错误、素材缺失才是确定性的。</p>
 */
public final class RetryableFailureClassifier {

  private RetryableFailureClassifier() {
  }

  public static boolean isRetryable(Throwable error) {
    if (error == null) {
      return false;
    }
    if (isRateLimitFailure(error)) {
      return true;
    }
    Throwable current = error;
    while (current != null) {
      if (current instanceof BusinessException business) {
        Integer code = business.getCode();
        if (Objects.equals(code, ErrorCode.AI_RATE_LIMIT_EXCEEDED.getCode())
            || Objects.equals(code, ErrorCode.AI_SERVICE_TIMEOUT.getCode())) {
          return true;
        }
      }
      if (current instanceof VisionRequestRejectedException) {
        return true;
      }
      Throwable cause = current.getCause();
      if (cause == current) {
        break;
      }
      current = cause;
    }
    if (StructuredOutputInvoker.isTransportFailure(error)) {
      return true;
    }
    return VisionFailureClassifier.canRetryAfterFailure(
        VisionFailureClassifier.classify(error));
  }

  /**
   * 上游 429 既可能以 SDK 的 {@link OpenAIServiceException} 出现，也可能被供应商包装成
   * 普通异常。这里统一识别，业务层不再各自解析错误码或文案。
   */
  public static boolean isRateLimitFailure(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof BusinessException business
          && Objects.equals(business.getCode(),
              ErrorCode.AI_RATE_LIMIT_EXCEEDED.getCode())) {
        return true;
      }
      if (current instanceof OpenAIServiceException service
          && service.statusCode() == 429) {
        return true;
      }
      if (isRateLimitMessage(current.getMessage())) {
        return true;
      }
      current = nextCause(current);
    }
    return false;
  }

  /**
   * 读取上游 {@code Retry-After} / {@code Retry-After-Ms}。没有响应头时返回 0，
   * 由调用方使用本地退避策略。
   */
  public static long retryAfterMillis(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof AiRateLimitExceededException rateLimit) {
        return Math.max(0, rateLimit.retryAfterMs());
      }
      if (current instanceof OpenAIServiceException service) {
        long retryAfter = parseRetryAfter(service.headers());
        if (retryAfter > 0) {
          return retryAfter;
        }
      }
      current = nextCause(current);
    }
    return 0;
  }

  private static long parseRetryAfter(Headers headers) {
    if (headers == null) {
      return 0;
    }
    long millis = parseRetryAfterMillis(firstHeader(headers, "retry-after-ms"));
    if (millis > 0) {
      return millis;
    }
    String value = firstHeader(headers, "retry-after");
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      double seconds = Double.parseDouble(value.trim());
      return Math.max(0, Math.round(seconds * 1000));
    } catch (NumberFormatException ignored) {
      return parseRetryAfterDate(value);
    }
  }

  private static long parseRetryAfterMillis(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Long.parseLong(value.trim()));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static long parseRetryAfterDate(String value) {
    try {
      ZonedDateTime retryAt = ZonedDateTime.parse(
          value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
      return Math.max(0, Duration.between(Instant.now(), retryAt.toInstant()).toMillis());
    } catch (DateTimeParseException ignored) {
      return 0;
    }
  }

  private static String firstHeader(Headers headers, String name) {
    List<String> values = headers.values(name);
    if (values != null && !values.isEmpty()) {
      return values.get(0);
    }
    for (String actual : headers.names()) {
      if (actual != null && actual.equalsIgnoreCase(name)) {
        values = headers.values(actual);
        if (values != null && !values.isEmpty()) {
          return values.get(0);
        }
      }
    }
    return null;
  }

  private static boolean isRateLimitMessage(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String lower = message.toLowerCase(Locale.ROOT);
    return lower.contains("429")
        || lower.contains("too many requests")
        || lower.contains("rate limit")
        || lower.contains("rate_limit")
        || message.contains("访问量过大")
        || message.contains("请求过于频繁")
        || message.contains("频率超限");
  }

  private static Throwable nextCause(Throwable cause) {
    Throwable next = cause.getCause();
    return next == cause ? null : next;
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
