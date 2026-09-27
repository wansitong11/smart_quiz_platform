package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.BatchSubmitAnswerRequest;
import interview.guide.modules.interview.model.BatchSubmitAnswerResponse;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.PaperTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("整卷交卷")
class InterviewSessionWholePaperTest {

  private static final String SESSION_ID = "whole-paper-session";

  @Mock
  private InterviewQuestionService questionService;
  @Mock
  private AnswerEvaluationService evaluationService;
  @Mock
  private InterviewPersistenceService persistenceService;
  @Mock
  private InterviewSessionCache sessionCache;
  @Mock
  private EvaluateStreamProducer evaluateStreamProducer;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private RedisService redisService;

  private ObjectMapper objectMapper;
  private InterviewSessionService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    service = new InterviewSessionService(
        questionService,
        evaluationService,
        persistenceService,
        sessionCache,
        objectMapper,
        evaluateStreamProducer,
        llmProviderRegistry,
        redisService
    );
  }

  private void givenCachedSession(SessionStatus status, int questionCount) {
    List<InterviewQuestionDTO> questions = new java.util.ArrayList<>();
    for (int i = 0; i < questionCount; i++) {
      questions.add(InterviewQuestionDTO.create(i, "第 " + (i + 1) + " 题题干", "SINGLE_CHOICE", "现代文阅读"));
    }
    CachedSession cached = new CachedSession(
        SESSION_ID, "", null, null, null,
        PaperTree.fromFlatQuestions(questions, "整卷测试"), 0, status, objectMapper);
    when(sessionCache.getSession(SESSION_ID)).thenReturn(Optional.of(cached));
  }

  @Nested
  @DisplayName("正常交卷")
  class SubmitTests {

    @Test
    @DisplayName("整卷提交后批量落库、置为 COMPLETED 并投递批改任务")
    void shouldSubmitWholePaper() {
      givenCachedSession(SessionStatus.CREATED, 3);
      List<BatchSubmitAnswerRequest.AnswerItem> items = List.of(
          new BatchSubmitAnswerRequest.AnswerItem(0, null, null, "A"),
          new BatchSubmitAnswerRequest.AnswerItem(2, null, null, "解答过程：……")
      );

      BatchSubmitAnswerResponse response = service.submitWholePaper(SESSION_ID, items);

      assertThat(response.answeredCount()).isEqualTo(2);
      assertThat(response.totalQuestions()).isEqualTo(3);
      assertThat(response.blankCount()).isZero();
      assertThat(response.status()).isEqualTo(SessionStatus.COMPLETED.name());
      assertThat(response.evaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING.name());

      @SuppressWarnings("unchecked")
      ArgumentCaptor<List<InterviewPersistenceService.AnswerSubmission>> captor =
          ArgumentCaptor.forClass(List.class);
      verify(persistenceService).saveWholePaperAnswers(eq(SESSION_ID), any(), captor.capture());
      assertThat(captor.getValue()).extracting(InterviewPersistenceService.AnswerSubmission::questionIndex)
          .containsExactly(0, 2);

      verify(sessionCache).updateSessionStatus(SESSION_ID, SessionStatus.COMPLETED);
      verify(sessionCache).updateCurrentIndex(SESSION_ID, 3);
      verify(persistenceService).updateEvaluateStatus(SESSION_ID, AsyncTaskStatus.PENDING, null);
      verify(evaluateStreamProducer).sendEvaluateTask(SESSION_ID);
    }

    @Test
    @DisplayName("跳题（空答案）允许提交，并计入空答案数量")
    void shouldAllowBlankAnswers() {
      givenCachedSession(SessionStatus.CREATED, 3);
      List<BatchSubmitAnswerRequest.AnswerItem> items = List.of(
          new BatchSubmitAnswerRequest.AnswerItem(0, null, null, "A"),
          new BatchSubmitAnswerRequest.AnswerItem(1, null, null, "   ")
      );

      BatchSubmitAnswerResponse response = service.submitWholePaper(SESSION_ID, items);

      assertThat(response.answeredCount()).isEqualTo(2);
      assertThat(response.blankCount()).isEqualTo(1);
      verify(evaluateStreamProducer).sendEvaluateTask(SESSION_ID);
    }
  }

  @Nested
  @DisplayName("校验失败")
  class ValidationTests {

    @Test
    @DisplayName("已交卷的会话不允许再次提交")
    void shouldRejectCompletedSession() {
      givenCachedSession(SessionStatus.COMPLETED, 2);

      assertThatThrownBy(() -> service.submitWholePaper(SESSION_ID,
          List.of(new BatchSubmitAnswerRequest.AnswerItem(0, null, null, "A"))))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getCode())
          .isEqualTo(ErrorCode.INTERVIEW_ALREADY_COMPLETED.getCode());

      verify(persistenceService, never()).saveWholePaperAnswers(anyString(), any(), any());
      verify(evaluateStreamProducer, never()).sendEvaluateTask(anyString());
    }

    @Test
    @DisplayName("题号越界时拒绝提交")
    void shouldRejectOutOfRangeIndex() {
      givenCachedSession(SessionStatus.IN_PROGRESS, 2);

      assertThatThrownBy(() -> service.submitWholePaper(SESSION_ID,
          List.of(new BatchSubmitAnswerRequest.AnswerItem(5, null, null, "A"))))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("无效的问题索引");

      verify(persistenceService, never()).saveWholePaperAnswers(anyString(), any(), any());
    }

    @Test
    @DisplayName("同一题号重复提交时拒绝，避免静默覆盖作答")
    void shouldRejectDuplicateIndex() {
      givenCachedSession(SessionStatus.IN_PROGRESS, 2);

      assertThatThrownBy(() -> service.submitWholePaper(SESSION_ID, List.of(
          new BatchSubmitAnswerRequest.AnswerItem(0, null, null, "A"),
          new BatchSubmitAnswerRequest.AnswerItem(0, null, null, "B"))))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("重复的问题索引");

      verify(persistenceService, never()).saveWholePaperAnswers(anyString(), any(), any());
    }

    @Test
    @DisplayName("答案列表为空时拒绝提交")
    void shouldRejectEmptyAnswers() {
      givenCachedSession(SessionStatus.IN_PROGRESS, 2);

      assertThatThrownBy(() -> service.submitWholePaper(SESSION_ID, List.of()))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("答案列表不能为空");
    }
  }
}
