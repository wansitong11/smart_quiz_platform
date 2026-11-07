package interview.guide.modules.voiceinterview.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.StreamWorkerRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音面试评估 Stream 消费者
 */
@Slf4j
@Component
public class VoiceEvaluateStreamConsumer extends AbstractStreamConsumer<VoiceEvaluateStreamConsumer.VoiceEvaluatePayload> {

    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;

    public VoiceEvaluateStreamConsumer(
        RedisService redisService,
        StreamWorkerRegistry workerRegistry,
        VoiceInterviewService voiceInterviewService,
        VoiceInterviewEvaluationService evaluationService
    ) {
        super(redisService, workerRegistry);
        this.voiceInterviewService = voiceInterviewService;
        this.evaluationService = evaluationService;
    }

    record VoiceEvaluatePayload(Long sessionId) {}

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String consumerKey() {
        return "voice-evaluate";
    }

    @Override
    protected String threadName() {
        return "voice-evaluate-consumer";
    }

    @Override
    protected VoiceEvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID);
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new VoiceEvaluatePayload(Long.parseLong(sessionId));
    }

    @Override
    protected String payloadIdentifier(VoiceEvaluatePayload payload) {
        return "voiceSessionId=" + payload.sessionId();
    }

    @Override
    protected void markProcessing(VoiceEvaluatePayload payload) {
        // 条件领取由 tryMarkProcessing 完成。
    }

    @Override
    protected boolean tryMarkProcessing(VoiceEvaluatePayload payload) {
        return voiceInterviewService.tryMarkEvaluateProcessing(payload.sessionId());
    }

    @Override
    protected void processBusiness(VoiceEvaluatePayload payload) {
        Long sessionId = payload.sessionId();
        evaluationService.generateEvaluation(sessionId);
        log.info("语音面试评估完成: sessionId={}", sessionId);
    }

    @Override
    protected void markCompleted(VoiceEvaluatePayload payload) {
        if (!voiceInterviewService.completeEvaluateIfProcessing(payload.sessionId())) {
            log.warn("语音面试评估完成状态未命中当前任务，ACK 丢弃: sessionId={}",
                payload.sessionId());
        }
    }

    @Override
    protected void markFailed(VoiceEvaluatePayload payload, String error) {
        if (!voiceInterviewService.failEvaluateIfProcessing(
            payload.sessionId(), truncateError(error))) {
            log.warn("语音面试评估失败状态未命中当前任务，ACK 丢弃: sessionId={}",
                payload.sessionId());
        }
    }

    @Override
    protected void retryMessage(VoiceEvaluatePayload payload, int retryCount) {
        Long sessionId = payload.sessionId();
        if (!voiceInterviewService.resetEvaluateToPending(sessionId)) {
            log.warn("语音评估重试重置失败，ACK 丢弃: sessionId={}, retryCount={}",
                sessionId, retryCount);
            return;
        }
        try {
            Map<String, String> message = new LinkedHashMap<>();
            message.put(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId.toString());
            message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
            message.putAll(aiContextMessageFields());

            redisService().streamAdd(
                AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("语音面试评估任务已重新入队: sessionId={}, retryCount={}", sessionId, retryCount);
        } catch (Exception e) {
            log.error("重试入队失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            voiceInterviewService.failEvaluateIfPending(
                sessionId, truncateError("重试入队失败: " + e.getMessage()));
        }
    }
}
