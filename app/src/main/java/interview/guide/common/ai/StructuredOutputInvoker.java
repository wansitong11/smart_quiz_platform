package interview.guide.common.ai;

import com.openai.errors.OpenAIIoException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 统一封装结构化输出调用与重试策略。
 */
@Component
public class StructuredOutputInvoker {

    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
    """;

    private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";
    private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";
    private static final String METRIC_LATENCY = "app.ai.structured_output.latency";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";
    private static final int MAX_CONTEXT_TAG_LENGTH = 48;
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");

    private final int maxAttempts;
    private final boolean includeLastErrorInRetryPrompt;
    private final boolean retryUseRepairPrompt;
    private final boolean retryAppendStrictJsonInstruction;
    private final int errorMessageMaxLength;
    private final boolean metricsEnabled;
    private final boolean schemaValidationEnabled;
    private final int retryBackoffBaseMs;
    private final int retryBackoffMaxMs;
    private final int retryBackoffJitterMs;
    private final int rateLimitBackoffBaseMs;
    private final int rateLimitBackoffMaxMs;
    private final int rateLimitBackoffJitterMs;
    private final int rateLimitMaxWaitMs;
    private final MeterRegistry meterRegistry;
    private final VisionRequestGuard visionRequestGuard;

    public StructuredOutputInvoker(
        StructuredOutputProperties properties,
        @Autowired(required = false) MeterRegistry meterRegistry,
        @Autowired(required = false) VisionRequestGuard visionRequestGuard
    ) {
        this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
        this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
        this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
        this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
        this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
        this.metricsEnabled = properties.isStructuredMetricsEnabled();
        this.schemaValidationEnabled = properties.isStructuredSchemaValidationEnabled();
        this.retryBackoffBaseMs = properties.getStructuredRetryBackoffBaseMs();
        this.retryBackoffMaxMs = properties.getStructuredRetryBackoffMaxMs();
        this.retryBackoffJitterMs = properties.getStructuredRetryBackoffJitterMs();
        this.rateLimitBackoffBaseMs = properties.getStructuredRateLimitBackoffBaseMs();
        this.rateLimitBackoffMaxMs = properties.getStructuredRateLimitBackoffMaxMs();
        this.rateLimitBackoffJitterMs = properties.getStructuredRateLimitBackoffJitterMs();
        this.rateLimitMaxWaitMs = properties.getStructuredRateLimitMaxWaitMs();
        this.meterRegistry = meterRegistry;
        this.visionRequestGuard = visionRequestGuard;
    }

    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, List.of(),
            outputConverter, errorCode, errorPrefix, logContext, log);
    }

    /**
     * 多模态重载：在 user 消息里附带图片等媒体（Spring AI {@code PromptUserSpec.media}）。
     * <p>
     * 重试时会把同一份媒体再发一次（图片 token 会重复计费），调用方需要自己限制重试次数。
     * </p>
     */
    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        List<Media> media,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, media, outputConverter,
            errorCode, errorPrefix, logContext, log, true);
    }

    /**
     * 不启用 Spring AI 的 JSON Schema 强校验：复杂嵌套 DTO 的可空字段会被 schema 生成器标成必填，
     * 模型省略可选字段时会误判失败。调用方自己负责语义校验。
     */
    public <T> T invokeWithoutSchemaValidation(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, List.of(), outputConverter,
            errorCode, errorPrefix, logContext, log, false);
    }

    /**
     * 明文文本结构化输出，并把模型原始回包交给调用方记录。
     *
     * <p>该入口只供需要完整诊断日志的业务链路使用；原始回包可能包含用户材料，调用方负责
     * 选择专用日志通道。</p>
     */
    public <T> T invokeWithoutSchemaValidation(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log,
        Consumer<String> rawResponseConsumer
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, List.of(), outputConverter,
            errorCode, errorPrefix, logContext, log, false, maxAttempts, false,
            rawResponseConsumer, null, null);
    }

    /**
     * 明文文本结构化输出，并在解析成功后执行业务语义校验。
     */
    public <T> T invokeWithoutSchemaValidation(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log,
        Predicate<T> semanticValidator,
        String semanticRetryInstruction
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, List.of(), outputConverter,
            errorCode, errorPrefix, logContext, log, false, maxAttempts, false,
            null, semanticValidator, semanticRetryInstruction);
    }

    /**
     * 明文文本结构化输出，同时记录原始回包并执行业务语义校验。
     */
    public <T> T invokeWithoutSchemaValidation(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log,
        Consumer<String> rawResponseConsumer,
        Predicate<T> semanticValidator,
        String semanticRetryInstruction
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, List.of(), outputConverter,
            errorCode, errorPrefix, logContext, log, false, maxAttempts, false,
            rawResponseConsumer, semanticValidator, semanticRetryInstruction);
    }

    /**
     * 多模态结构化输出，且不使用框架 JSON Schema 强校验。
     * 适用于可选字段较多、Schema 生成会把可空字段误标为必填的 DTO。
     */
    public <T> T invokeWithoutSchemaValidation(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        List<Media> media,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, media, outputConverter,
            errorCode, errorPrefix, logContext, log, false);
    }

    /**
     * 多模态结构化输出，并把模型原始回包交给调用方记录。
     */
    public <T> T invokeWithoutSchemaValidation(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        List<Media> media,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log,
        Consumer<String> rawResponseConsumer
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, media, outputConverter,
            errorCode, errorPrefix, logContext, log, false, maxAttempts, false,
            rawResponseConsumer, null, null);
    }

    /**
     * 视觉链路的单次 provider 调用入口。模型调用失败不在本类内重发，
     * 由页面编排按失败类别决定是否重抽。
     */
    public <T> T invokeVisionOnceWithoutSchemaValidation(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        List<Media> media,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, media, outputConverter,
            errorCode, errorPrefix, logContext, log, false, 1, true, null, null, null);
    }

    /**
     * 视觉链路单次调用，并完整记录模型原始回包。
     */
    public <T> T invokeVisionOnceWithoutSchemaValidation(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        List<Media> media,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log,
        Consumer<String> rawResponseConsumer
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, media, outputConverter,
            errorCode, errorPrefix, logContext, log, false, 1, true,
            rawResponseConsumer, null, null);
    }

    private <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        List<Media> media,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log,
        boolean validateSchema
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, media, outputConverter,
            errorCode, errorPrefix, logContext, log, validateSchema, maxAttempts, false, null,
            null, null);
    }

    private <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        List<Media> media,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log,
        boolean validateSchema,
        int attemptLimit,
        boolean guardVisionRequest,
        Consumer<String> rawResponseConsumer,
        Predicate<T> semanticValidator,
        String semanticRetryInstruction
    ) {
        long startNanos = System.nanoTime();
        String contextTag = normalizeContextTag(logContext);
        String securedSystemPrompt = systemPromptWithFormat
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        Exception lastError = null;
        int attempts = Math.max(1, attemptLimit);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String attemptSystemPrompt = attempt == 1
                ? securedSystemPrompt
                : buildAttemptSystemPrompt(
                    securedSystemPrompt, lastError, semanticRetryInstruction);
            try {
                T result = invokeProvider(
                    chatClient, attemptSystemPrompt, userPrompt, media, outputConverter, logContext,
                    log, validateSchema, guardVisionRequest, rawResponseConsumer);
                if (semanticValidator != null && !semanticValidator.test(result)) {
                    throw new StructuredOutputSemanticException(semanticRetryInstruction);
                }
                recordAttempt(contextTag, STATUS_SUCCESS);
                recordInvocation(contextTag, STATUS_SUCCESS, startNanos);
                return result;
            } catch (VisionRequestRejectedException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                recordAttempt(contextTag, STATUS_FAILURE);
                if (attempt < attempts) {
                    logRetry(log, logContext, attempt, attempts, e);
                    if (sleepBeforeRetry(e, attempt, attempts, log, logContext)) {
                        break;
                    }
                } else {
                    log.error("{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
                        logContext, attempts, ErrorLogSanitizer.summarize(e),
                        ErrorLogSanitizer.forLogging(e));
                }
            }
        }

        recordInvocation(contextTag, STATUS_FAILURE, startNanos);
        ErrorCode resolvedErrorCode = RetryableFailureClassifier.isRateLimitFailure(lastError)
            ? ErrorCode.AI_RATE_LIMIT_EXCEEDED
            : errorCode;
        throw new BusinessException(
            resolvedErrorCode,
            failureMessage(errorPrefix, lastError),
            lastError
        );
    }

    private void logRetry(
        Logger log, String logContext, int attempt, int attempts, Exception error) {
        if (error instanceof StructuredOutputSemanticException) {
            log.warn("{}结构化语义校验失败，准备定向重试: attempt={}/{}",
                logContext, attempt, attempts);
            return;
        }
        log.warn("{}结构化解析失败，准备重试: attempt={}/{}, error={}",
            logContext, attempt, attempts, ErrorLogSanitizer.summarize(error),
            ErrorLogSanitizer.forLogging(error));
    }

    private boolean sleepBeforeRetry(
        Exception error, int attempt, int attempts, Logger log, String logContext) {
        if (!RetryableFailureClassifier.isRetryable(error)) {
            return false;
        }
        long delay = retryBackoffDelayMs(error, attempt);
        if (delay <= 0) {
            return false;
        }
        if (RetryableFailureClassifier.isRateLimitFailure(error)) {
            log.warn("{}命中上游限流，退避后重试: attempt={}/{}, delayMs={}",
                logContext, attempt, attempts, delay);
        }
        try {
            sleepRetryBackoff(delay);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    protected void sleepRetryBackoff(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private long retryBackoffDelayMs(Exception error, int attempt) {
        long upstreamRetryAfter = RetryableFailureClassifier.retryAfterMillis(error);
        if (upstreamRetryAfter > 0) {
            return capDelay(upstreamRetryAfter, rateLimitMaxWaitMs);
        }
        if (RetryableFailureClassifier.isRateLimitFailure(error)) {
            return rateLimitBackoffDelayMs(attempt);
        }
        long base = Math.max(0, retryBackoffBaseMs);
        long exponent = 1L << Math.max(0, Math.min(20, attempt - 1));
        long exponential = base * exponent;
        long capped = Math.min(exponential, Math.max(base, retryBackoffMaxMs));
        long jitter = retryBackoffJitterMs <= 0
            ? 0
            : ThreadLocalRandom.current().nextLong(Math.max(1, retryBackoffJitterMs));
        return Math.max(0, capped + jitter);
    }

    private long rateLimitBackoffDelayMs(int attempt) {
        long base = Math.max(0, rateLimitBackoffBaseMs);
        if (base == 0) {
            return 0;
        }
        long exponent = 1L << Math.max(0, Math.min(20, attempt - 1));
        long exponential = base * exponent;
        long capped = Math.min(exponential, Math.max(base, rateLimitBackoffMaxMs));
        long jitter = rateLimitBackoffJitterMs <= 0
            ? 0
            : ThreadLocalRandom.current().nextLong(
                Math.max(1, rateLimitBackoffJitterMs));
        return capDelay(Math.max(0, capped + jitter), rateLimitMaxWaitMs);
    }

    private long capDelay(long delay, int maxWaitMs) {
        long safeDelay = Math.max(0, delay);
        long limit = Math.max(0, maxWaitMs);
        return limit > 0 ? Math.min(safeDelay, limit) : safeDelay;
    }

    private <T> T invokeProvider(
        ChatClient chatClient,
        String systemPrompt,
        String userPrompt,
        List<Media> media,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log,
        boolean validateSchema,
        boolean guardVisionRequest,
        Consumer<String> rawResponseConsumer
    ) {
        if (!guardVisionRequest || visionRequestGuard == null) {
            return callStructuredOutput(
                chatClient, systemPrompt, userPrompt, media, outputConverter, logContext, log,
                validateSchema, rawResponseConsumer);
        }
        return visionRequestGuard.execute(() -> callStructuredOutput(
            chatClient, systemPrompt, userPrompt, media, outputConverter, logContext, log,
            validateSchema, rawResponseConsumer));
    }

    static String failureMessage(String errorPrefix, Throwable error) {
        String prefix = errorPrefix == null ? "" : errorPrefix;
        if (isTransportFailure(error)) {
            return prefix + "AI 服务连接失败或响应超时，请稍后重试";
        }
        return prefix + "请稍后重试";
    }

    public static boolean isTransportFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof OpenAIIoException
                || current instanceof SocketTimeoutException
                || current instanceof HttpTimeoutException
                || current instanceof TimeoutException
                || current instanceof ResourceAccessException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private <T> T callStructuredOutput(
        ChatClient chatClient,
        String systemPrompt,
        String userPrompt,
        List<Media> media,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log,
        boolean validateSchema,
        Consumer<String> rawResponseConsumer
    ) {
        var call = chatClient.prompt()
            .system(systemPrompt)
            .user(spec -> {
                spec.text(userPrompt);
                if (media != null && !media.isEmpty()) {
                    spec.media(media.toArray(Media[]::new));
                }
            })
            .call();
        if (schemaValidationEnabled && validateSchema) {
            return call.entity(outputConverter, spec -> spec.validateSchema());
        }
        String content = call.content();
        if (rawResponseConsumer != null) {
            try {
                rawResponseConsumer.accept(content);
            } catch (RuntimeException loggingError) {
                log.warn("{}原始模型回包记录失败", logContext, loggingError);
            }
        }
        return convertWithRepair(content, outputConverter, logContext, log);
    }

    private <T> T convertWithRepair(
        String content,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log
    ) {
        content = stripMarkdownFence(content);
        try {
            return outputConverter.convert(content);
        } catch (Exception firstError) {
            String repaired = repairUnescapedQuotesInJsonStrings(content);
            if (!repaired.equals(content)) {
                try {
                    T result = outputConverter.convert(repaired);
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
                    return result;
                } catch (Exception repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

    private String stripMarkdownFence(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
            return trimmed.substring(firstLineEnd + 1, lastFence).trim();
        }
        return trimmed;
    }

    private String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    private boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    private String buildAttemptSystemPrompt(
        String systemPromptWithFormat,
        Exception lastError,
        String semanticRetryInstruction
    ) {
        if (lastError instanceof StructuredOutputSemanticException) {
            return buildSemanticRetrySystemPrompt(
                systemPromptWithFormat, semanticRetryInstruction);
        }
        return buildRetrySystemPrompt(systemPromptWithFormat, lastError);
    }

    private String buildSemanticRetrySystemPrompt(
        String systemPromptWithFormat,
        String semanticRetryInstruction
    ) {
        StringBuilder prompt = new StringBuilder(systemPromptWithFormat);
        if (retryAppendStrictJsonInstruction) {
            prompt.append("\n\n").append(STRICT_JSON_INSTRUCTION);
        }
        prompt.append("\n\n上次输出虽然能解析，但内容不满足要求。");
        if (semanticRetryInstruction != null && !semanticRetryInstruction.isBlank()) {
            prompt.append("\n").append(semanticRetryInstruction);
        }
        return prompt.toString();
    }

    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat;
        }

        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
            .append("\n\n");

        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");

        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }

    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "...";
        }
        return oneLine;
    }

    private void recordAttempt(String contextTag, String status) {
        if (!isMetricsAvailable()) {
            return;
        }
        meterRegistry.counter(
            METRIC_ATTEMPTS,
            Tags.of("context", contextTag, "status", status)
        ).increment();
    }

    private void recordInvocation(String contextTag, String status, long startNanos) {
        if (!isMetricsAvailable()) {
            return;
        }
        Tags tags = Tags.of("context", contextTag, "status", status);
        meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
        meterRegistry.timer(METRIC_LATENCY, tags)
            .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private boolean isMetricsAvailable() {
        return metricsEnabled && meterRegistry != null;
    }

    private String normalizeContextTag(String raw) {
        String source = (raw == null || raw.isBlank()) ? "unknown" : raw;
        String normalized = source.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        if (normalized.length() > MAX_CONTEXT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_TAG_LENGTH);
        }
        return normalized;
    }
}
