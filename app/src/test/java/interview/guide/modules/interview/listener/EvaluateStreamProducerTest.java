package interview.guide.modules.interview.listener;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("批改任务生产者")
class EvaluateStreamProducerTest {

  private static final String SESSION_ID = "session-1";

  @Mock
  private RedisService redisService;
  @Mock
  private InterviewSessionRepository sessionRepository;
  @Mock
  private TransactionalExecutor transactionalExecutor;

  @AfterEach
  void clearContext() {
    AiCallContextHolder.clear();
  }

  @Test
  @DisplayName("同步入口无上下文时从会话归属补 ownerId 与批改功能")
  void shouldFallbackToSessionOwnerWhenContextMissing() {
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setOwnerId(1L);
    when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(session));
    EvaluateStreamProducer producer = new EvaluateStreamProducer(
        redisService, sessionRepository, transactionalExecutor);

    Map<String, String> message = producer.buildMessage(SESSION_ID);

    assertThat(message)
        .containsEntry(AsyncTaskStreamConstants.FIELD_SESSION_ID, SESSION_ID)
        .containsEntry(AsyncTaskStreamConstants.FIELD_OWNER_ID, "1")
        .containsEntry(AsyncTaskStreamConstants.FIELD_AI_FEATURE,
            AiFeature.ANSWER_EVALUATE.name());
  }

  @Test
  @DisplayName("已有调用上下文时优先沿用入口用户与功能归因")
  void shouldKeepExistingContext() {
    AiCallContextHolder.set(new AiCallContext(
        9L, AiFeature.ANSWER_EVALUATE, "submit", "request-1", "operation-1", "SYNC"));
    EvaluateStreamProducer producer = new EvaluateStreamProducer(
        redisService, sessionRepository, transactionalExecutor);

    Map<String, String> message = producer.buildMessage(SESSION_ID);

    assertThat(message)
        .doesNotContainKey(AsyncTaskStreamConstants.FIELD_OWNER_ID)
        .doesNotContainKey(AsyncTaskStreamConstants.FIELD_AI_FEATURE);
    verify(sessionRepository, never()).findBySessionId(SESSION_ID);
  }
}
