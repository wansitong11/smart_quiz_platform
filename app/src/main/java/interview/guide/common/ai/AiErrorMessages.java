package interview.guide.common.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * 把底层 AI SDK 的异常翻译成用户能看懂的原因。
 * <p>
 * 典型场景：阿里云百炼免费额度用尽时返回
 * {@code 403: Free quota exhausted...}，如果统一显示"AI服务暂时不可用"，
 * 用户会误判成网络故障，白白排查半天。
 */
public final class AiErrorMessages {

  private AiErrorMessages() {
  }

  /**
   * @return 能识别的错误说明；识别不了返回 empty，由调用方走各自的兜底文案
   */
  public static Optional<String> describe(Throwable error) {
    for (Throwable cause = error; cause != null; cause = nextCause(cause)) {
      String message = cause.getMessage();
      if (message == null || message.isBlank()) {
        continue;
      }
      String lower = message.toLowerCase(Locale.ROOT);

      if (containsAny(lower, "free quota", "quota exhausted", "exceeded your current quota",
          "insufficient balance", "arrearage", "insufficient quota")) {
        return Optional.of("AI 模型额度不足：免费额度已用尽或账户欠费，"
            + "请在百炼控制台充值（或关闭「仅使用免费额度」）后重试");
      }
      if (containsAny(lower, "invalid api key", "incorrect api key", "unauthorized", "authentication")) {
        return Optional.of("AI 密钥无效或未授权，请检查 AI_BAILIAN_API_KEY 配置");
      }
      if (containsAny(lower, "429", "too many requests", "rate limit")) {
        return Optional.of("AI 调用频率超限，请稍后重试");
      }
      if (containsAny(lower, "model not found", "model does not exist", "invalid model",
          "no permission to access model")) {
        return Optional.of("AI 模型不存在或当前账号无权限访问，请检查 AI_MODEL 配置");
      }
      if (containsAny(lower, "timeout", "timed out", "connection reset", "connection refused",
          "unknown host", "network is unreachable")) {
        return Optional.of("连接 AI 服务失败，请检查服务器出网与代理设置");
      }
    }
    return Optional.empty();
  }

  private static Throwable nextCause(Throwable cause) {
    Throwable next = cause.getCause();
    // 自引用时终止，避免死循环
    return next == cause ? null : next;
  }

  private static boolean containsAny(String text, String... keywords) {
    for (String keyword : keywords) {
      if (text.contains(keyword)) {
        return true;
      }
    }
    return false;
  }
}
