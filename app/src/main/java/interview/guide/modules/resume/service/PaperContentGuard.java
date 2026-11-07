package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity.SessionStatus;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 试卷内容修改与作答开始共用的单卷护栏。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaperContentGuard {

  public static final long LOCK_WAIT_SECONDS = 5;
  private static final String LOCK_PREFIX = "paper:content-lock:";
  private static final String PAPER_SOURCE_TYPE = "PAPER";

  private final RedisService redisService;
  private final InterviewSessionRepository sessionRepository;

  public <T> T withPaperLock(Long paperId, Supplier<T> action) {
    return redisService.executeWithLock(
        LOCK_PREFIX + paperId,
        LOCK_WAIT_SECONDS,
        TimeUnit.SECONDS,
        action::get
    );
  }

  public void withPaperLock(Long paperId, Runnable action) {
    withPaperLock(paperId, () -> {
      action.run();
      return null;
    });
  }

  /**
   * 内容变更前，把已打开但尚未产生任何答案的会话退回草稿。
   * <p>
   * 试卷切题后会自动创建练习会话，用户点开作答页后会话会变成
   * {@link SessionStatus#IN_PROGRESS}。如果此时还没有保存任何答案，
   * 重新切题不会丢失用户输入，可以安全地让旧会话过期。
   */
  public void prepareForContentChange(Long paperId) {
    List<PracticeSessionEntity> sessions =
        sessionRepository.findUnstartedInProgressPaperAttempts(
            paperId, PAPER_SOURCE_TYPE, SessionStatus.IN_PROGRESS);
    for (PracticeSessionEntity session : sessions) {
      session.setStatus(SessionStatus.CREATED);
      sessionRepository.save(session);
      log.info("内容变更前释放未作答会话: paperId={}, sessionId={}",
          paperId, session.getSessionId());
    }
    assertNoActiveAttempt(paperId);
  }

  public void assertNoActiveAttempt(Long paperId) {
    boolean active = sessionRepository.existsActivePaperAttempt(
        paperId,
        PAPER_SOURCE_TYPE,
        SessionStatus.IN_PROGRESS,
        SessionStatus.COMPLETED,
        AsyncTaskStatus.COMPLETED
    );
    if (active) {
      throw new BusinessException(
          ErrorCode.PAPER_CONTENT_LOCKED,
          "当前试卷有进行中的作答或批改，暂不能修改内容"
      );
    }
  }

  /**
   * 只读查询：是否存在会阻止内容修改的进行中会话。
   */
  public boolean hasActiveAttempt(Long paperId) {
    return sessionRepository.existsActivePaperAttempt(
        paperId,
        PAPER_SOURCE_TYPE,
        SessionStatus.IN_PROGRESS,
        SessionStatus.COMPLETED,
        AsyncTaskStatus.COMPLETED
    );
  }
}
