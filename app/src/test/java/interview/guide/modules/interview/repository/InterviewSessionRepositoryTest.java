package interview.guide.modules.interview.repository;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity.SessionStatus;
import interview.guide.modules.resume.model.PracticePaperEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = InterviewSessionRepositoryTest.JpaConfig.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:interview-session-repository;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
    })
@Transactional
@DisplayName("试卷活动会话查询")
class InterviewSessionRepositoryTest {

  @Autowired
  private InterviewSessionRepository repository;

  @Autowired
  private EntityManager entityManager;

  @Test
  @DisplayName("进行中的 PAPER 会话阻止内容修改")
  void inProgressPaperAttemptIsActive() {
    PracticePaperEntity paper = persistPaper("in-progress");
    persistSession(paper, "in-progress-session", SessionStatus.IN_PROGRESS, null, "PAPER");

    assertThat(existsActiveAttempt(paper)).isTrue();
  }

  @Test
  @DisplayName("已交卷但批改未完成的 PAPER 会话仍阻止内容修改")
  void completedAttemptWithoutCompletedEvaluationIsActive() {
    PracticePaperEntity failedEvaluation = persistPaper("evaluation-failed");
    persistSession(failedEvaluation, "evaluation-failed-session", SessionStatus.COMPLETED,
        AsyncTaskStatus.FAILED, "PAPER");

    PracticePaperEntity missingEvaluation = persistPaper("evaluation-missing");
    persistSession(missingEvaluation, "evaluation-missing-session", SessionStatus.COMPLETED,
        null, "PAPER");

    assertThat(existsActiveAttempt(failedEvaluation)).isTrue();
    assertThat(existsActiveAttempt(missingEvaluation)).isTrue();
  }

  @Test
  @DisplayName("批改完成或已生成报告的会话不阻止内容修改")
  void finishedPaperAttemptIsNotActive() {
    PracticePaperEntity evaluated = persistPaper("evaluated");
    persistSession(evaluated, "evaluated-session", SessionStatus.COMPLETED,
        AsyncTaskStatus.COMPLETED, "PAPER");

    PracticePaperEntity reported = persistPaper("reported");
    persistSession(reported, "reported-session", SessionStatus.EVALUATED,
        AsyncTaskStatus.COMPLETED, "PAPER");

    assertThat(existsActiveAttempt(evaluated)).isFalse();
    assertThat(existsActiveAttempt(reported)).isFalse();
  }

  @Test
  @DisplayName("非 PAPER 来源的进行中会话不影响试卷内容")
  void nonPaperAttemptDoesNotBlockContentChange() {
    PracticePaperEntity paper = persistPaper("normal-session");
    persistSession(paper, "normal-session-id", SessionStatus.IN_PROGRESS, null, "NORMAL");

    assertThat(existsActiveAttempt(paper)).isFalse();
  }

  @Test
  @DisplayName("只查找没有答案的进行中 PAPER 会话")
  void findsOnlyUnstartedPaperAttempts() {
    PracticePaperEntity paper = persistPaper("unstarted-attempt");
    persistSession(paper, "unstarted-session", SessionStatus.IN_PROGRESS, null, "PAPER");
    PracticeSessionEntity answered = persistSession(
        paper, "answered-session", SessionStatus.IN_PROGRESS, null, "PAPER");
    PracticeAnswerEntity answer = new PracticeAnswerEntity();
    answer.setSession(answered);
    answer.setQuestionIndex(0);
    answer.setUserAnswer("A");
    entityManager.persist(answer);
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.findUnstartedInProgressPaperAttempts(
        paper.getId(), "PAPER", SessionStatus.IN_PROGRESS))
        .extracting(PracticeSessionEntity::getSessionId)
        .containsExactly("unstarted-session");
  }

  private boolean existsActiveAttempt(PracticePaperEntity paper) {
    entityManager.flush();
    entityManager.clear();
    return repository.existsActivePaperAttempt(
        paper.getId(),
        "PAPER",
        SessionStatus.IN_PROGRESS,
        SessionStatus.COMPLETED,
        AsyncTaskStatus.COMPLETED
    );
  }

  private PracticePaperEntity persistPaper(String fileHash) {
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setFileHash(fileHash);
    paper.setOriginalFilename(fileHash + ".pdf");
    paper.setSourceType("PDF");
    entityManager.persist(paper);
    return paper;
  }

  private PracticeSessionEntity persistSession(
      PracticePaperEntity paper,
      String sessionId,
      SessionStatus status,
      AsyncTaskStatus evaluateStatus,
      String sourceType
  ) {
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setSessionId(sessionId);
    session.setResume(paper);
    session.setSourceType(sourceType);
    session.setStatus(status);
    session.setEvaluateStatus(evaluateStatus);
    session.setTotalQuestions(1);
    entityManager.persist(session);
    return session;
  }

  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = {PracticePaperEntity.class, PracticeSessionEntity.class})
  @EnableJpaRepositories(basePackageClasses = InterviewSessionRepository.class)
  static class JpaConfig {
  }
}
