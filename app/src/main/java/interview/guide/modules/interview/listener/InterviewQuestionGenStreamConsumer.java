package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.StreamWorkerRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.SessionQuestionGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 面试出题消费者：分发线程领取消息，Stream worker 并发生成不同会话。
 */
@Slf4j
@Component
public class InterviewQuestionGenStreamConsumer
    extends AbstractStreamConsumer<InterviewQuestionGenStreamConsumer.QuestionGenPayload> {

    private final InterviewSessionRepository sessionRepository;
    private final SessionQuestionGenerationService generationService;

    public InterviewQuestionGenStreamConsumer(
        RedisService redisService,
        StreamWorkerRegistry workerRegistry,
        InterviewSessionRepository sessionRepository,
        SessionQuestionGenerationService generationService
    ) {
        super(redisService, workerRegistry);
        this.sessionRepository = sessionRepository;
        this.generationService = generationService;
    }

    static final class QuestionGenPayload {

        private final String sessionId;
        private final int retryCount;
        private String attemptId;

        QuestionGenPayload(String sessionId, int retryCount) {
            this.sessionId = sessionId;
            this.retryCount = retryCount;
        }

        String sessionId() {
            return sessionId;
        }

        int retryCount() {
            return retryCount;
        }

        String attemptId() {
            return attemptId;
        }

        void setAttemptId(String attemptId) {
            this.attemptId = attemptId;
        }
    }

    @Override
    protected String taskDisplayName() {
        return "面试出题";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_QUESTION_GEN_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_QUESTION_GEN_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.INTERVIEW_QUESTION_GEN_CONSUMER_PREFIX;
    }

    @Override
    protected String consumerKey() {
        return "interview-question-gen";
    }

    @Override
    protected String threadName() {
        return "interview-question-gen-consumer";
    }

    @Override
    protected QuestionGenPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("出题消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        int retryCount = parseRetryCount(data);
        return new QuestionGenPayload(sessionId, retryCount);
    }

    @Override
    protected String payloadIdentifier(QuestionGenPayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected boolean shouldSkip(QuestionGenPayload payload) {
        return sessionRepository.findBySessionId(payload.sessionId())
            .map(session -> session.getQuestionStatus() == AsyncTaskStatus.COMPLETED)
            .orElse(true);
    }

    @Override
    protected void markProcessing(QuestionGenPayload payload) {
        // 条件领取模式下不使用
    }

    @Override
    protected boolean tryMarkProcessing(QuestionGenPayload payload) {
        String attemptId = UUID.randomUUID().toString();
        boolean claimed = sessionRepository.tryMarkQuestionGenProcessing(
            payload.sessionId(), attemptId, LocalDateTime.now()) == 1;
        if (claimed) {
            payload.setAttemptId(attemptId);
        }
        return claimed;
    }

    @Override
    protected void processBusiness(QuestionGenPayload payload) {
        generationService.generate(payload.sessionId(), payload.attemptId());
    }

    @Override
    protected void markCompleted(QuestionGenPayload payload) {
        // SessionQuestionGenerationService.generate 已原子写入 COMPLETED 并刷新缓存
    }

    @Override
    protected void markFailed(QuestionGenPayload payload, String error) {
        sessionRepository.failQuestionGenIfProcessing(
            payload.sessionId(), payload.attemptId(), truncateError(error), LocalDateTime.now());
    }

    @Override
    protected void retryMessage(QuestionGenPayload payload, int retryCount) {
        int reset = sessionRepository.resetQuestionGenToPending(
            payload.sessionId(), payload.attemptId(), LocalDateTime.now());
        if (reset != 1) {
            log.warn("出题重试重置失败，ACK 丢弃: sessionId={}, retryCount={}",
                payload.sessionId(), retryCount);
            return;
        }
        try {
            Map<String, String> message = new LinkedHashMap<>();
            message.put(AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId());
            message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
            message.putAll(aiContextMessageFields());
            redisService().streamAdd(
                AsyncTaskStreamConstants.INTERVIEW_QUESTION_GEN_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
        } catch (Exception e) {
            sessionRepository.failQuestionGenIfPending(
                payload.sessionId(), "重试入队失败", LocalDateTime.now());
        }
    }
}
