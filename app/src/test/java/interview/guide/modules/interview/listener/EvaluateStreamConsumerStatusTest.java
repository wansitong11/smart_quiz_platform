package interview.guide.modules.interview.listener;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.async.StreamWorkerRegistry;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.quiz.service.WrongQuestionBookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("评估消费者条件领取")
class EvaluateStreamConsumerStatusTest {

  @Mock
  private RedisService redisService;
  @Mock
  private StreamWorkerRegistry workerRegistry;
  @Mock
  private InterviewSessionRepository sessionRepository;
  @Mock
  private AnswerEvaluationService evaluationService;
  @Mock
  private InterviewPersistenceService persistenceService;
  @Mock
  private ObjectMapper objectMapper;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private WrongQuestionBookService wrongQuestionBookService;

  private EvaluateStreamConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new EvaluateStreamConsumer(
        redisService,
        workerRegistry,
        sessionRepository,
        evaluationService,
        persistenceService,
        objectMapper,
        llmProviderRegistry,
        wrongQuestionBookService
    );
  }

  @Test
  @DisplayName("只有 PENDING 转 PROCESSING 成功才执行批改")
  void claimsOnlyPendingTask() {
    when(sessionRepository.tryMarkEvaluateProcessing(
        eq("session-1"), anyString(), any(LocalDateTime.class))).thenReturn(1);
    when(sessionRepository.tryMarkEvaluateProcessing(
        eq("session-2"), anyString(), any(LocalDateTime.class))).thenReturn(0);

    assertThat(consumer.tryMarkProcessing(new EvaluateStreamConsumer.EvaluatePayload("session-1")))
        .isTrue();
    assertThat(consumer.tryMarkProcessing(new EvaluateStreamConsumer.EvaluatePayload("session-2")))
        .isFalse();
  }

  @Test
  @DisplayName("完成、失败和重试都使用条件状态更新")
  void usesConditionalTerminalUpdates() {
    EvaluateStreamConsumer.EvaluatePayload payload =
        new EvaluateStreamConsumer.EvaluatePayload("session-1");

    consumer.markCompleted(payload);
    consumer.markFailed(payload, "失败");
    consumer.retryMessage(payload, 1);

    verify(sessionRepository).completeEvaluateIfProcessing("session-1");
    verify(sessionRepository).failEvaluateIfProcessing("session-1", "失败");
    verify(sessionRepository).resetEvaluateToPending("session-1");
  }
}
