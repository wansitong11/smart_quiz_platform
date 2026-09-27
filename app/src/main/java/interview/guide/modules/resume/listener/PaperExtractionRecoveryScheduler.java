package interview.guide.modules.resume.listener;

import interview.guide.common.async.recovery.ResumeAnalysisRecoveryProperties;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 试卷切题卡住任务恢复调度器（P1-07）。语义与 VectorizeRecoveryScheduler 一致。
 */
@Slf4j
@Component
public class PaperExtractionRecoveryScheduler {

  private final ResumeRepository resumeRepository;
  private final PaperExtractionStreamProducer producer;
  private final ResumeAnalysisRecoveryProperties properties;

  public PaperExtractionRecoveryScheduler(ResumeRepository resumeRepository,
                                          PaperExtractionStreamProducer producer,
                                          ResumeAnalysisRecoveryProperties properties) {
    this.resumeRepository = resumeRepository;
    this.producer = producer;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${app.async.recovery.resume-analyze.interval-ms:60000}",
      initialDelayString = "${app.async.recovery.resume-analyze.interval-ms:60000}")
  public void recoverStuckTasks() {
    if (!properties.isEnabled()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    List<PracticePaperEntity> stalePending = resumeRepository.findStaleByAnalysisStatus(
        AsyncTaskStatus.PENDING, now.minus(properties.getPendingThreshold()),
        PageRequest.of(0, properties.getBatchSize()));
    for (PracticePaperEntity paper : stalePending) {
      recover(paper.getId(), true);
    }
    List<PracticePaperEntity> staleProcessing = resumeRepository.findStaleByAnalysisStatus(
        AsyncTaskStatus.PROCESSING, now.minus(properties.getProcessingThreshold()),
        PageRequest.of(0, properties.getBatchSize()));
    for (PracticePaperEntity paper : staleProcessing) {
      int reset = resumeRepository.resetStaleAnalyzeProcessing(
          paper.getId(), LocalDateTime.now().minus(properties.getProcessingThreshold()), LocalDateTime.now());
      if (reset == 1) {
        recover(paper.getId(), false);
      }
    }
  }

  private void recover(Long paperId, boolean alreadyPending) {
    LocalDateTime now = LocalDateTime.now();
    if (alreadyPending) {
      int touched = resumeRepository.touchQueuedAnalyzeForRecovery(
          paperId, LocalDateTime.now().minus(properties.getPendingThreshold()), now);
      if (touched != 1) {
        return;
      }
    }
    resumeRepository.incrementAnalysisRecoveryCount(paperId);
    PracticePaperEntity paper = resumeRepository.findById(paperId).orElse(null);
    if (paper == null) {
      return;
    }
    if (paper.getAnalysisRecoveryCount() >= properties.getMaxRecoveryCount()) {
      resumeRepository.failAnalyzeIfPending(paperId,
          "自动恢复次数达到上限（" + paper.getAnalysisRecoveryCount() + "），请手动重试", LocalDateTime.now());
      log.warn("试卷切题任务自动恢复达到上限转 FAILED: paperId={}, recoveryCount={}",
          paperId, paper.getAnalysisRecoveryCount());
      return;
    }
    boolean sent = producer.sendExtractTask(paperId);
    log.info("试卷切题卡住任务已补投: paperId={}, sent={}, recoveryCount={}",
        paperId, sent, paper.getAnalysisRecoveryCount());
  }
}
