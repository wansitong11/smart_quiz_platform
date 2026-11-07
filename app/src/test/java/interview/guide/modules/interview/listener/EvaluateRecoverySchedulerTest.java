package interview.guide.modules.interview.listener;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiFeature;
import interview.guide.modules.interview.config.InterviewEvaluationRecoveryProperties;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("批改恢复调度器")
class EvaluateRecoverySchedulerTest {

  @Mock
  private InterviewSessionRepository sessionRepository;
  @Mock
  private EvaluateStreamProducer producer;

  private InterviewEvaluationRecoveryProperties properties;
  private EvaluateRecoveryScheduler scheduler;

  @BeforeEach
  void setUp() {
    properties = new InterviewEvaluationRecoveryProperties();
    properties.setEnabled(true);
    scheduler = new EvaluateRecoveryScheduler(sessionRepository, producer, properties);
  }

  @Test
  @DisplayName("补投时恢复会话 owner 与批改功能归因，限流上下文不丢失")
  void shouldRestoreAiContextWhenRequeueing() {
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setSessionId("session-stale");
    session.setOwnerId(7L);
    when(sessionRepository.findStaleEvaluateProcessing(
        any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(session));
    when(sessionRepository.resetStaleEvaluateProcessing(
        eq("session-stale"), any(LocalDateTime.class))).thenReturn(1);

    AtomicReference<AiCallContextHolder.AiCallContext> sentContext = new AtomicReference<>();
    org.mockito.Mockito.doAnswer(invocation -> {
      sentContext.set(AiCallContextHolder.get());
      return null;
    }).when(producer).sendEvaluateTask("session-stale");

    scheduler.recoverStuckTasks();

    verify(producer).sendEvaluateTask("session-stale");
    assertThat(sentContext.get()).isNotNull();
    assertThat(sentContext.get().userId()).isEqualTo(7L);
    assertThat(sentContext.get().feature()).isEqualTo(AiFeature.ANSWER_EVALUATE);
    assertThat(AiCallContextHolder.get()).isNull();
  }
}
