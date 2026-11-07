package interview.guide.common.ai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.List;

/**
 * 把 Jackson 解析异常转成可安全写进重试提示的最小纠正信息。
 *
 * <p>只提取路径、目标类型和非法短值；完整模型回包、异常堆栈和用户正文不得进入提示。</p>
 */
public final class JsonFailureFeedbackFormatter {

  private static final int MAX_ACTUAL_CHARS = 40;
  private static final int MIN_INSTRUCTION_CHARS = 20;

  private JsonFailureFeedbackFormatter() {
  }

  public static JsonFailureFeedback extract(Throwable error) {
    JacksonException jacksonError = findJacksonError(error);
    if (jacksonError == null) {
      return null;
    }
    String location = renderPath(jacksonError.getPath());
    String expected = "可解析的 JSON 值";
    String actual = "";
    if (jacksonError instanceof InvalidFormatException invalidFormat) {
      Class<?> targetType = invalidFormat.getTargetType();
      if (targetType != null) {
        expected = describeType(targetType);
      }
      actual = describeValue(invalidFormat.getValue());
    }
    return new JsonFailureFeedback(location, expected, actual);
  }

  public static String toInstruction(JsonFailureFeedback feedback, int maxChars) {
    if (feedback == null) {
      return "";
    }
    String actual = truncate(feedback.actual(), MAX_ACTUAL_CHARS);
    String instruction = "上一次输出的 " + nullSafe(feedback.location())
        + " 期望是 " + nullSafe(feedback.expected())
        + "，但收到 " + (actual.isBlank() ? "空值" : actual)
        + "。请把该字段改成正确类型；text 必须是字符串，bbox 必须是恰好 4 个数字组成的数组。";
    return truncate(instruction, Math.max(MIN_INSTRUCTION_CHARS, maxChars));
  }

  private static JacksonException findJacksonError(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof JacksonException jacksonError) {
        return jacksonError;
      }
      Throwable cause = current.getCause();
      if (cause == current) {
        break;
      }
      current = cause;
    }
    return null;
  }

  private static String renderPath(List<JacksonException.Reference> path) {
    if (path == null || path.isEmpty()) {
      return "<unknown>";
    }
    StringBuilder result = new StringBuilder();
    for (JacksonException.Reference reference : path) {
      if (reference == null) {
        continue;
      }
      String property = reference.getPropertyName();
      int index = reference.getIndex();
      if (property != null && !property.isBlank()) {
        if (result.length() > 0) {
          result.append('.');
        }
        result.append(property);
      }
      if (index >= 0) {
        result.append('[').append(index).append(']');
      }
    }
    return result.isEmpty() ? "<unknown>" : result.toString();
  }

  private static String describeType(Class<?> targetType) {
    if (targetType == double.class || targetType == Double.class) {
      return "数字（double）";
    }
    if (targetType == int.class || targetType == Integer.class) {
      return "整数（int）";
    }
    return targetType.getTypeName();
  }

  private static String describeValue(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof String text) {
      return "\"" + truncate(text, MAX_ACTUAL_CHARS) + "\"";
    }
    return truncate(String.valueOf(value), MAX_ACTUAL_CHARS);
  }

  private static String truncate(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    String oneLine = text.replace('\n', ' ').replace('\r', ' ').strip();
    if (oneLine.length() <= maxChars) {
      return oneLine;
    }
    return oneLine.substring(0, Math.max(0, maxChars)) + "…";
  }

  private static String nullSafe(String text) {
    return text == null || text.isBlank() ? "<unknown>" : text;
  }
}
