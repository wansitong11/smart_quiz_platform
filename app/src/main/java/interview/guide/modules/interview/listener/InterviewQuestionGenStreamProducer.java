package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 面试会话出题任务生产者。
 */
@Slf4j
@Component
public class InterviewQuestionGenStreamProducer
    extends AbstractStreamProducer<InterviewQuestionGenStreamProducer.QuestionGenTaskPayload> {

    private final InterviewSessionRepository sessionRepository;

    record QuestionGenTaskPayload(String sessionId) {
    }

    public InterviewQuestionGenStreamProducer(RedisService redisService,
                                              InterviewSessionRepository sessionRepository) {
        super(redisService);
        this.sessionRepository = sessionRepository;
    }

    public boolean sendQuestionGenTask(String sessionId) {
        return sendTask(new QuestionGenTaskPayload(sessionId));
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
    protected Map<String, String> buildMessage(QuestionGenTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(QuestionGenTaskPayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected void onSendFailed(QuestionGenTaskPayload payload, String error) {
        sessionRepository.failQuestionGenIfPending(
            payload.sessionId(), truncateError(error), LocalDateTime.now());
    }
}
