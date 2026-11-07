package interview.guide.modules.interview.listener;

import interview.guide.common.async.recovery.QuestionGenRecoveryProperties;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试出题卡住任务恢复调度器：语义与试卷识别恢复一致。
 */
@Slf4j
@Component
public class QuestionGenRecoveryScheduler {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewQuestionGenStreamProducer producer;
    private final QuestionGenRecoveryProperties properties;

    public QuestionGenRecoveryScheduler(InterviewSessionRepository sessionRepository,
                                        InterviewQuestionGenStreamProducer producer,
                                        QuestionGenRecoveryProperties properties) {
        this.sessionRepository = sessionRepository;
        this.producer = producer;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.async.recovery.question-gen.interval-ms:60000}",
        initialDelayString = "${app.async.recovery.question-gen.interval-ms:60000}")
    public void recoverStuckTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<PracticeSessionEntity> stalePending = sessionRepository.findStaleByQuestionStatus(
            AsyncTaskStatus.PENDING, now.minus(properties.getPendingThreshold()),
            PageRequest.of(0, properties.getBatchSize()));
        for (PracticeSessionEntity session : stalePending) {
            recover(session.getSessionId(), true,
                now.minus(properties.getPendingThreshold()), now);
        }
        List<PracticeSessionEntity> staleProcessing = sessionRepository.findStaleByQuestionStatus(
            AsyncTaskStatus.PROCESSING, now.minus(properties.getProcessingThreshold()),
            PageRequest.of(0, properties.getBatchSize()));
        for (PracticeSessionEntity session : staleProcessing) {
            int reset = sessionRepository.resetQuestionGenToPending(
                session.getSessionId(), session.getQuestionAttemptId(), now);
            if (reset == 1) {
                recover(session.getSessionId(), false, now, now);
            }
        }
    }

    private void recover(String sessionId, boolean alreadyPending,
                         LocalDateTime threshold, LocalDateTime now) {
        if (alreadyPending) {
            int touched = sessionRepository.touchQueuedQuestionGenForRecovery(
                sessionId, threshold, now);
            if (touched != 1) {
                return;
            }
        }
        PracticeSessionEntity session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        int nextCount = session.getQuestionRecoveryCount() + 1;
        if (nextCount >= properties.getMaxRecoveryCount()) {
            sessionRepository.incrementQuestionGenRecoveryCount(sessionId);
            sessionRepository.failQuestionGenIfPending(
                sessionId,
                "自动恢复次数达到上限（" + nextCount + "），请手动重试",
                LocalDateTime.now());
            log.warn("面试出题自动恢复达到上限: sessionId={}, recoveryCount={}",
                sessionId, nextCount);
            return;
        }
        sessionRepository.incrementQuestionGenRecoveryCount(sessionId);
        producer.sendQuestionGenTask(sessionId);
    }
}
