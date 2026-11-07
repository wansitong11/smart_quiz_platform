package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis Stream 生产者模板基类。
 * 统一消息发送骨架与失败处理逻辑。
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    private final RedisService redisService;

    protected AbstractStreamProducer(RedisService redisService) {
        this.redisService = redisService;
    }

    protected boolean sendTask(T payload) {
        try {
            Map<String, String> message = new LinkedHashMap<>(buildMessage(payload));
            attachCallContext(message);
            String messageId = redisService.streamAdd(
                streamKey(),
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("{}任务已发送到Stream: {}, messageId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId);
            return true;
        } catch (Exception e) {
            logSendFailure(payload, e);
            onSendFailed(payload, "任务入队失败");
            return false;
        }
    }

    private void attachCallContext(Map<String, String> message) {
        AiCallContext context = AiCallContextHolder.get();
        if (context == null) {
            return;
        }
        if (context.userId() != null) {
            message.putIfAbsent(AsyncTaskStreamConstants.FIELD_OWNER_ID, context.userId().toString());
        }
        if (context.feature() != null) {
            message.putIfAbsent(AsyncTaskStreamConstants.FIELD_AI_FEATURE, context.feature().name());
        }
        if (context.operation() != null) {
            message.putIfAbsent(AsyncTaskStreamConstants.FIELD_AI_OPERATION, context.operation());
        }
        if (context.operationId() != null) {
            message.putIfAbsent(AsyncTaskStreamConstants.FIELD_AI_OPERATION_ID, context.operationId());
        }
        if (context.requestId() != null) {
            message.putIfAbsent(AsyncTaskStreamConstants.FIELD_AI_REQUEST_ID, context.requestId());
        }
    }

    /**
     * 业务子类可覆盖此方法接入专用诊断日志；默认保持其他模块原有脱敏行为。
     */
    protected void logSendFailure(T payload, Exception error) {
        log.error("发送{}任务失败: {}, error={}",
            taskDisplayName(), payloadIdentifier(payload), ErrorLogSanitizer.summarize(error),
            ErrorLogSanitizer.forLogging(error));
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract Map<String, String> buildMessage(T payload);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void onSendFailed(T payload, String error);
}
