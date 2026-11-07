package interview.guide.modules.interview.listener;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiFeature;
import interview.guide.modules.interview.config.InterviewEvaluationRecoveryProperties;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 批改卡住任务恢复：心跳过期后把 PROCESSING 重置为 PENDING 并补投。
 */
@Slf4j
@Component
public class EvaluateRecoveryScheduler {

  private final InterviewSessionRepository sessionRepository;
  private final EvaluateStreamProducer producer;
  private final InterviewEvaluationRecoveryProperties properties;

  public EvaluateRecoveryScheduler(InterviewSessionRepository sessionRepository,
                                   EvaluateStreamProducer producer,
                                   InterviewEvaluationRecoveryProperties properties) {
    this.sessionRepository = sessionRepository;
    this.producer = producer;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${app.async.recovery.interview-evaluate.interval-ms:60000}",
      initialDelayString = "${app.async.recovery.interview-evaluate.interval-ms:60000}")
  public void recoverStuckTasks() {
    if (!properties.isEnabled()) {
      return;
    }
    LocalDateTime threshold = LocalDateTime.now().minus(properties.getProcessingThreshold());
    List<PracticeSessionEntity> stale = sessionRepository.findStaleEvaluateProcessing(
        threshold, PageRequest.of(0, properties.getBatchSize()));
    for (PracticeSessionEntity session : stale) {
      int reset = sessionRepository.resetStaleEvaluateProcessing(
          session.getSessionId(), threshold);
      if (reset == 1) {
        sendWithContext(session);
        log.warn("批改卡住任务已重置并补投: sessionId={}", session.getSessionId());
      }
    }
  }

  private void sendWithContext(PracticeSessionEntity session) {
    AiCallContext previous = AiCallContextHolder.get();
    AiCallContextHolder.set(new AiCallContext(
        session.getOwnerId(), AiFeature.ANSWER_EVALUATE, "recover", null, null, "RECOVERY"));
    try {
      producer.sendEvaluateTask(session.getSessionId());
    } finally {
      if (previous == null) {
        AiCallContextHolder.clear();
      } else {
        AiCallContextHolder.set(previous);
      }
    }
  }
}
