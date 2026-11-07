package interview.guide.common.ai;

/**
 * 答案键文本的统一判定口径。
 *
 * <p>切题、答案键生成、作答校验和批改必须共用同一套"是否缺失"规则，
 * 否则同一份数据在不同阶段会被判成不同结果。</p>
 */
public final class AnswerKeySupport {

  private AnswerKeySupport() {
  }

  public static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * 空串、纯空白、占位符都视为缺失。
   */
  public static boolean isMissing(String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    String normalized = value.replaceAll("\\s+", "").trim();
    return "暂缺".equals(normalized)
        || "无法确定".equals(normalized)
        || "N/A".equalsIgnoreCase(normalized);
  }
}
