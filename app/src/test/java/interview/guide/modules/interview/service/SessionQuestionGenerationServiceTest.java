package interview.guide.modules.interview.service;

import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.model.QuestionGenRequestSnapshot;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("异步出题 worker")
class SessionQuestionGenerationServiceTest {

  private static final String SESSION_ID = "session-async";
  private static final String ATTEMPT_ID = "attempt-1";

  @Mock
  private InterviewQuestionService questionService;
  @Mock
  private InterviewPersistenceService persistenceService;
  @Mock
  private InterviewSessionRepository sessionRepository;
  @Mock
  private InterviewSessionCache sessionCache;

  @Test
  @DisplayName("数据库完成写入后缓存刷新失败不应把任务反向标记为失败")
  void cacheFailureDoesNotFailCompletedGeneration() {
    ObjectMapper objectMapper = new ObjectMapper();
    PracticeSessionEntity entity = shell(objectMapper);
    InterviewQuestionDTO question =
        InterviewQuestionDTO.create(0, "什么是 JVM？", "JVM", "基础知识");
    when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(entity));
    when(sessionRepository.heartbeatQuestionGenProcessing(
        eq(SESSION_ID), eq(ATTEMPT_ID), any())).thenReturn(1);
    when(persistenceService.getHistoricalQuestions("chinese", null)).thenReturn(List.of());
    when(questionService.generateQuestionsBySkill(
        "glm", "chinese", "mid", "项目经历", 1, List.<HistoricalQuestion>of(), null, null))
        .thenReturn(List.of(question));
    when(sessionRepository.completeQuestionGenIfProcessing(
        eq(SESSION_ID), eq(ATTEMPT_ID), anyString(), eq(1), any())).thenReturn(1);
    doThrow(new IllegalStateException("redis unavailable"))
        .when(sessionCache)
        .saveSession(anyString(), anyString(), any(), any(), any(), any(), anyInt(), any());

    SessionQuestionGenerationService service = new SessionQuestionGenerationService(
        questionService,
        persistenceService,
        sessionRepository,
        sessionCache,
        objectMapper
    );

    assertThatCode(() -> service.generate(SESSION_ID, ATTEMPT_ID)).doesNotThrowAnyException();
    verify(sessionRepository).completeQuestionGenIfProcessing(
        eq(SESSION_ID), eq(ATTEMPT_ID), anyString(), eq(1), any());
  }

  private PracticeSessionEntity shell(ObjectMapper objectMapper) {
    PracticeSessionEntity entity = new PracticeSessionEntity();
    entity.setSessionId(SESSION_ID);
    entity.setQuestionGenRequestJson(objectMapper.writeValueAsString(
        new QuestionGenRequestSnapshot(
            "项目经历", null, "chinese", "mid", 1, null, null, "glm")));
    return entity;
  }
}
