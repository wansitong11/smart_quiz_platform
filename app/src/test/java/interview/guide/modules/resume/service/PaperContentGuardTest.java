package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity.SessionStatus;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("试卷内容护栏")
class PaperContentGuardTest {

  private final RedisService redisService = mock(RedisService.class);
  private final InterviewSessionRepository sessionRepository =
      mock(InterviewSessionRepository.class);
  private final PaperContentGuard guard =
      new PaperContentGuard(redisService, sessionRepository);

  @Test
  @DisplayName("单卷锁内执行短操作")
  void executesInsidePaperLock() {
    when(redisService.executeWithLock(
        eq("paper:content-lock:7"), anyLong(), any(TimeUnit.class), any()))
        .thenAnswer(invocation -> {
          RedisService.LockedOperation<?> operation = invocation.getArgument(3);
          return operation.execute();
        });

    String result = guard.withPaperLock(7L, () -> "ok");

    assertThat(result).isEqualTo("ok");
  }

  @Test
  @DisplayName("存在进行中作答或未完成批改时拒绝修改")
  void rejectsContentChangeWhenActiveAttemptExists() {
    when(sessionRepository.existsActivePaperAttempt(
        7L, "PAPER", SessionStatus.IN_PROGRESS, SessionStatus.COMPLETED,
        AsyncTaskStatus.COMPLETED))
        .thenReturn(true);

    assertThatThrownBy(() -> guard.assertNoActiveAttempt(7L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("进行中的作答或批改");
  }

  @Test
  @DisplayName("内容变更前把未作答的进行中会话退回草稿")
  void releasesUnstartedAttemptBeforeContentChange() {
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setSessionId("unstarted");
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setCurrentQuestionIndex(0);
    when(sessionRepository.findUnstartedInProgressPaperAttempts(
        7L, "PAPER", SessionStatus.IN_PROGRESS))
        .thenReturn(java.util.List.of(session));

    guard.prepareForContentChange(7L);

    assertThat(session.getStatus()).isEqualTo(SessionStatus.CREATED);
    verify(sessionRepository).save(session);
  }
}
