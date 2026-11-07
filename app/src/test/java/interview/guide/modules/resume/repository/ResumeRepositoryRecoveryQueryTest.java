package interview.guide.modules.resume.repository;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.RecognitionStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = ResumeRepositoryRecoveryQueryTest.JpaConfig.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:resume-recovery-query;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
    })
@Transactional
@DisplayName("切题恢复查询")
class ResumeRepositoryRecoveryQueryTest {

  @Autowired
  private ResumeRepository repository;

  @Autowired
  private EntityManager entityManager;

  @Test
  @DisplayName("只扫描正文就绪且已确认或纯文字的试卷")
  void scansOnlyExtractablePapers() {
    PracticePaperEntity unconfirmed = paper(
        "unconfirmed", RecognitionStatus.NEEDS_REVIEW, null);
    PracticePaperEntity confirmed = paper(
        "confirmed", RecognitionStatus.EXTRACTING, "已确认的正文");
    PracticePaperEntity textOnly = paper(
        "text-only", RecognitionStatus.NONE, "纯文字正文");
    repository.saveAll(List.of(unconfirmed, confirmed, textOnly));
    entityManager.flush();
    entityManager.clear();

    List<PracticePaperEntity> stale = repository.findStaleExtractableByAnalysisStatus(
        AsyncTaskStatus.PENDING, LocalDateTime.now().minusMinutes(10), PageRequest.of(0, 10));

    assertThat(stale)
        .extracting(PracticePaperEntity::getFileHash)
        .containsExactlyInAnyOrder("confirmed", "text-only");
  }

  @Test
  @DisplayName("touch 守卫不会推进未确认试卷")
  void touchDoesNotAdvanceUnconfirmedPaper() {
    PracticePaperEntity unconfirmed = repository.save(paper(
        "touch-unconfirmed", RecognitionStatus.NEEDS_REVIEW, null));
    PracticePaperEntity confirmed = repository.save(paper(
        "touch-confirmed", RecognitionStatus.EXTRACTING, "正文"));
    entityManager.flush();

    LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
    int unconfirmedTouched = repository.touchQueuedAnalyzeForRecovery(
        unconfirmed.getId(), threshold, LocalDateTime.now());
    int confirmedTouched = repository.touchQueuedAnalyzeForRecovery(
        confirmed.getId(), threshold, LocalDateTime.now());

    assertThat(unconfirmedTouched).isZero();
    assertThat(confirmedTouched).isEqualTo(1);
  }

  @Test
  @DisplayName("reset 守卫不会重置未确认试卷")
  void resetDoesNotResetUnconfirmedPaper() {
    PracticePaperEntity unconfirmed = paper(
        "reset-unconfirmed", RecognitionStatus.NEEDS_REVIEW, null);
    unconfirmed.setAnalysisStatus(AsyncTaskStatus.PROCESSING);
    unconfirmed = repository.save(unconfirmed);

    PracticePaperEntity confirmed = paper(
        "reset-confirmed", RecognitionStatus.EXTRACTING, "正文");
    confirmed.setAnalysisStatus(AsyncTaskStatus.PROCESSING);
    confirmed = repository.save(confirmed);
    entityManager.flush();

    LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
    int unconfirmedReset = repository.resetStaleAnalyzeProcessing(
        unconfirmed.getId(), threshold, LocalDateTime.now());
    int confirmedReset = repository.resetStaleAnalyzeProcessing(
        confirmed.getId(), threshold, LocalDateTime.now());

    assertThat(unconfirmedReset).isZero();
    assertThat(confirmedReset).isEqualTo(1);
  }

  @Test
  @DisplayName("切题处理失败时已确认试卷回到 READY")
  void failedProcessingRestoresConfirmedPaperToReady() {
    PracticePaperEntity paper = paper(
        "failed-processing", RecognitionStatus.EXTRACTING, "正文");
    paper.setAnalysisStatus(AsyncTaskStatus.PROCESSING);
    paper.setAnalysisAttemptId("attempt-1");
    paper = repository.save(paper);
    entityManager.flush();

    int updated = repository.failAnalyzeIfProcessing(
        paper.getId(), "attempt-1", "ANSWER_KEY: 答案候选不能为空", LocalDateTime.now());
    entityManager.flush();
    entityManager.clear();

    assertThat(updated).isEqualTo(1);
    PracticePaperEntity failed = repository.findById(paper.getId()).orElseThrow();
    assertThat(failed.getAnalysisStatus()).isEqualTo(AsyncTaskStatus.FAILED);
    assertThat(failed.getRecognitionStatus()).isEqualTo(RecognitionStatus.READY);
    assertThat(failed.getAnalysisAttemptId()).isNull();
  }

  @Test
  @DisplayName("切题入队失败时已确认试卷回到 READY")
  void failedPendingRestoresConfirmedPaperToReady() {
    PracticePaperEntity paper = repository.save(paper(
        "failed-pending", RecognitionStatus.EXTRACTING, "正文"));
    entityManager.flush();

    int updated = repository.failAnalyzeIfPending(
        paper.getId(), "切题任务投递失败", LocalDateTime.now());
    entityManager.flush();
    entityManager.clear();

    assertThat(updated).isEqualTo(1);
    PracticePaperEntity failed = repository.findById(paper.getId()).orElseThrow();
    assertThat(failed.getAnalysisStatus()).isEqualTo(AsyncTaskStatus.FAILED);
    assertThat(failed.getRecognitionStatus()).isEqualTo(RecognitionStatus.READY);
  }

  @Test
  @DisplayName("纯文字试卷失败后保持 NONE")
  void failedTextOnlyPaperKeepsRecognitionNone() {
    PracticePaperEntity paper = paper(
        "failed-text-only", RecognitionStatus.NONE, "纯文字正文");
    paper.setAnalysisStatus(AsyncTaskStatus.PROCESSING);
    paper.setAnalysisAttemptId("attempt-1");
    paper = repository.save(paper);
    entityManager.flush();

    repository.failAnalyzeIfProcessing(
        paper.getId(), "attempt-1", "STRUCTURE: 模型请求失败", LocalDateTime.now());
    entityManager.flush();
    entityManager.clear();

    PracticePaperEntity failed = repository.findById(paper.getId()).orElseThrow();
    assertThat(failed.getAnalysisStatus()).isEqualTo(AsyncTaskStatus.FAILED);
    assertThat(failed.getRecognitionStatus()).isEqualTo(RecognitionStatus.NONE);
  }

  private PracticePaperEntity paper(
      String fileHash,
      RecognitionStatus recognitionStatus,
      String resumeText
  ) {
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setOwnerId(1L);
    paper.setFileHash(fileHash);
    paper.setOriginalFilename(fileHash + ".pdf");
    paper.setSourceType("PDF");
    paper.setRecognitionStatus(recognitionStatus);
    paper.setResumeText(resumeText);
    paper.setAnalysisStatus(AsyncTaskStatus.PENDING);
    paper.setAnalysisUpdatedAt(LocalDateTime.now().minusMinutes(20));
    return paper;
  }

  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = PracticePaperEntity.class)
  @EnableJpaRepositories(basePackageClasses = ResumeRepository.class)
  static class JpaConfig {
  }
}
