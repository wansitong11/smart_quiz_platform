package interview.guide.modules.interview.listener;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面试评估任务生产者
 * 负责发送评估任务到 Redis Stream
 */
@Slf4j
@Component
public class EvaluateStreamProducer extends AbstractStreamProducer<String> {

    private final InterviewSessionRepository sessionRepository;
    private final TransactionalExecutor transactionalExecutor;

    public EvaluateStreamProducer(
        RedisService redisService,
        InterviewSessionRepository sessionRepository,
        TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.sessionRepository = sessionRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 发送评估任务到 Redis Stream
     *
     * @param sessionId 面试会话ID
     */
    public void sendEvaluateTask(String sessionId) {
        sendTask(sessionId);
    }

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(String sessionId) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put(AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId);
        message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");

        AiCallContext context = AiCallContextHolder.get();
        if (context == null || context.feature() == null) {
            message.put(AsyncTaskStreamConstants.FIELD_AI_FEATURE,
                AiFeature.ANSWER_EVALUATE.name());
        }
        if (context == null || context.userId() == null) {
            sessionRepository.findBySessionId(sessionId)
                .map(session -> session.getOwnerId())
                .filter(ownerId -> ownerId != null)
                .ifPresent(ownerId -> message.put(
                    AsyncTaskStreamConstants.FIELD_OWNER_ID, ownerId.toString()));
        }
        return message;
    }

    @Override
    protected String payloadIdentifier(String sessionId) {
        return "sessionId=" + sessionId;
    }

    @Override
    protected void onSendFailed(String sessionId, String error) {
        transactionalExecutor.runRequiresNew(
            () -> updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, truncateError(error)));
    }

    /**
     * 更新评估状态
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            sessionRepository.save(session);
        });
    }
}
