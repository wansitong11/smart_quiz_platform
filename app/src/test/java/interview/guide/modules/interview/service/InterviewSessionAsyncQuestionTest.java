package interview.guide.modules.interview.service;

import interview.guide.common.ai.AiCostControl;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.listener.InterviewQuestionGenStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试会话异步出题")
class InterviewSessionAsyncQuestionTest {

  @Mock
  private InterviewQuestionService questionService;
  @Mock
  private InterviewPersistenceService persistenceService;
  @Mock
  private InterviewSessionCache sessionCache;
  @Mock
  private EvaluateStreamProducer evaluateStreamProducer;
  @Mock
  private InterviewQuestionGenStreamProducer questionGenStreamProducer;
  @Mock
  private RedisService redisService;
  @Mock
  private AiCostControl aiCostControl;

  private InterviewSessionService service;

  @BeforeEach
  void setUp() {
    service = new InterviewSessionService(
        questionService,
        persistenceService,
        sessionCache,
        new ObjectMapper(),
        evaluateStreamProducer,
        questionGenStreamProducer,
        redisService,
        aiCostControl
    );
  }

  @Test
  @DisplayName("创建请求只保存壳会话并投递出题任务，不在请求线程调用 LLM")
  void createsShellSessionAndEnqueuesGeneration() {
    when(questionGenStreamProducer.sendQuestionGenTask(anyString())).thenReturn(true);
    CreateInterviewRequest request = request();

    InterviewSessionDTO result = service.createSession(request);

    assertThat(result.questionStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(result.questions()).isEmpty();
    assertThat(result.totalQuestions()).isZero();
    verify(persistenceService).saveQuestionShell(
        eq(result.sessionId()), isNull(), isNull(), eq("glm"), eq("chinese"), eq("mid"),
        anyString());
    verify(questionGenStreamProducer).sendQuestionGenTask(result.sessionId());
    verify(questionService, never()).generateQuestionsBySkill(
        any(), anyString(), anyString(), any(), anyInt(), any(), any(), any());
  }

  @Test
  @DisplayName("缓存写入失败不能阻断已经落库的壳会话投递出题任务")
  void stillEnqueuesWhenShellCacheWriteFails() {
    when(questionGenStreamProducer.sendQuestionGenTask(anyString())).thenReturn(true);
    doThrow(new IllegalStateException("redis unavailable"))
        .when(sessionCache)
        .saveSession(anyString(), anyString(), any(), any(), any(), any(), anyInt(), any());

    assertThatCode(() -> service.createSession(request())).doesNotThrowAnyException();

    verify(questionGenStreamProducer).sendQuestionGenTask(anyString());
  }

  private CreateInterviewRequest request() {
    return new CreateInterviewRequest(
        "项目经历",
        8,
        null,
        true,
        "glm",
        "chinese",
        "mid",
        null,
        null,
        null
    );
  }
}
