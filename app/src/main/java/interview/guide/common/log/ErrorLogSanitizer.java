package interview.guide.common.log;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 异常日志消息简化器。
 *
 * <p>SDK 与上游服务可能把请求体、Prompt 片段回显到异常 message 中，直接记录存在隐私风险。
 * 原始 message、cause 和 suppressed exception 都可能回显用户输入，因此不得直接进入日志。
 * 本类只补充可结构化的安全诊断信息（异常链、Jackson 路径/非法标量/目标类型），
 * 需要堆栈时使用 {@link #forLogging(Throwable)} 生成脱敏副本。
 */
public final class ErrorLogSanitizer {

    private static final int MAX_DETAIL_LENGTH = 500;
    private static final int MAX_VALUE_LENGTH = 160;

    private ErrorLogSanitizer() {
    }

    /**
     * 输出异常链和安全诊断信息，不输出可能包含 Prompt、转写或文件正文的 message。
     */
    public static String summarize(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        StringBuilder result = new StringBuilder();
        appendSummary(result, e, newIdentitySet());
        return limit(result.toString(), MAX_DETAIL_LENGTH);
    }

    /**
     * 将外部服务返回的自由文本归一为固定标记，避免响应正文或用户输入被回显。
     */
    public static String summarize(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return "external-error";
    }

    /**
     * 返回可安全作为 SLF4J 最后一个参数记录的异常副本。
     *
     * <p>副本保留原始调用栈、cause 链和 suppressed 链的结构，但每个节点都只使用
     * {@link #summarizeSingle(Throwable)} 生成的安全 message。
     */
    public static Throwable forLogging(Throwable error) {
        return sanitizedCopy(error, newIdentitySet());
    }

    private static void appendSummary(
        StringBuilder result,
        Throwable error,
        Set<Throwable> seen
    ) {
        if (error == null || !seen.add(error)) {
            result.append("cycle");
            return;
        }
        if (result.length() > 0) {
            result.append(" <- ");
        }
        result.append(summarizeSingle(error));
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            appendSummary(result, cause, seen);
        }
    }

    private static String summarizeSingle(Throwable error) {
        StringBuilder result = new StringBuilder("type=")
            .append(error.getClass().getSimpleName());
        if (error instanceof JacksonException jacksonError) {
            appendJacksonDetails(result, jacksonError);
        }
        return result.toString();
    }

    private static void appendJacksonDetails(
        StringBuilder result,
        JacksonException error
    ) {
        String path = safeText(error.getPathReference());
        if (!path.isBlank()) {
            result.append(", path=").append(path);
        }
        if (error instanceof InvalidFormatException invalidFormatError) {
            result.append(", invalidValue=")
                .append(formatValue(invalidFormatError.getValue()));
        }
        if (error instanceof MismatchedInputException mismatchedInputError) {
            Class<?> targetType = mismatchedInputError.getTargetType();
            if (targetType != null) {
                result.append(", targetType=").append(targetType.getTypeName());
            }
        }
    }

    private static Throwable sanitizedCopy(
        Throwable error,
        Set<Throwable> seen
    ) {
        if (error == null) {
            return new SanitizedLogException("unknown", null);
        }
        if (!seen.add(error)) {
            return new SanitizedLogException("cycle", null);
        }

        Throwable cause = error.getCause();
        SanitizedLogException copy = new SanitizedLogException(
            summarizeSingle(error),
            cause == null || cause == error ? null : sanitizedCopy(cause, seen)
        );
        copy.setStackTrace(error.getStackTrace());
        for (Throwable suppressed : error.getSuppressed()) {
            copy.addSuppressed(sanitizedCopy(suppressed, seen));
        }
        seen.remove(error);
        return copy;
    }

    private static Set<Throwable> newIdentitySet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static String safeText(String text) {
        return text == null ? "" : limit(text, MAX_DETAIL_LENGTH);
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence) {
            return "\"" + limit(escape(value.toString()), MAX_VALUE_LENGTH) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean
            || value instanceof Character) {
            return limit(escape(value.toString()), MAX_VALUE_LENGTH);
        }
        return "<" + value.getClass().getSimpleName() + ">";
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t");
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated)";
    }

    private static final class SanitizedLogException extends Exception {

        private SanitizedLogException(String detail, Throwable cause) {
            super("sanitized-error " + detail, cause, false, true);
        }
    }
}
