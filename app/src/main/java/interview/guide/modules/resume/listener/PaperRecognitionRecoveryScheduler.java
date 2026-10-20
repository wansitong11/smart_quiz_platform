package interview.guide.modules.resume.listener;

import interview.guide.common.async.recovery.PaperRecognitionRecoveryProperties;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 试卷识别卡住任务恢复调度器：语义与切题恢复一致，只是换了状态字段。
 */
@Slf4j
@Component
public class PaperRecognitionRecoveryScheduler {

  private final ResumeRepository resumeRepository;
  private final PaperRecognitionStreamProducer producer;
  private final PaperRecognitionRecoveryProperties properties;

  public PaperRecognitionRecoveryScheduler(ResumeRepository resumeRepository,
                                           PaperRecognitionStreamProducer producer,
                                           PaperRecognitionRecoveryProperties properties) {
    this.resumeRepository = resumeRepository;
    this.producer = producer;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${app.async.recovery.paper-recognition.interval-ms:60000}",
      initialDelayString = "${app.async.recovery.paper-recognition.interval-ms:60000}")
  public void recoverStuckTasks() {
    if (!properties.isEnabled()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    List<PracticePaperEntity> stalePending = resumeRepository.findStaleByRecognitionStatus(
        RecognitionStatus.PENDING, now.minus(properties.getPendingThreshold()),
        PageRequest.of(0, properties.getBatchSize()));
    for (PracticePaperEntity paper : stalePending) {
      recover(paper.getId(), true);
    }
    List<PracticePaperEntity> staleProcessing = resumeRepository.findStaleByRecognitionStatus(
        RecognitionStatus.PROCESSING, now.minus(properties.getProcessingThreshold()),
        PageRequest.of(0, properties.getBatchSize()));
    for (PracticePaperEntity paper : staleProcessing) {
      int reset = resumeRepository.resetStaleRecognitionProcessing(
          paper.getId(), now.minus(properties.getProcessingThreshold()), now);
      if (reset == 1) {
        recover(paper.getId(), false);
      }
    }
  }

  private void recover(Long paperId, boolean alreadyPending) {
    LocalDateTime now = LocalDateTime.now();
    if (alreadyPending) {
      int touched = resumeRepository.touchQueuedRecognitionForRecovery(
          paperId, now.minus(properties.getPendingThreshold()), now);
      if (touched != 1) {
        return;
      }
    }
    resumeRepository.incrementRecognitionRecoveryCount(paperId);
    PracticePaperEntity paper = resumeRepository.findById(paperId).orElse(null);
    if (paper == null) {
      return;
    }
    if (paper.getRecognitionRecoveryCount() >= properties.getMaxRecoveryCount()) {
      resumeRepository.failRecognitionIfPending(paperId,
          "自动恢复次数达到上限（" + paper.getRecognitionRecoveryCount() + "），请手动重试",
          LocalDateTime.now());
      log.warn("试卷识别自动恢复达到上限: paperId={}, recoveryCount={}",
          paperId, paper.getRecognitionRecoveryCount());
      return;
    }
    boolean sent = producer.sendRecognitionTask(paperId);
    log.info("试卷识别卡住任务已补投: paperId={}, sent={}, recoveryCount={}",
        paperId, sent, paper.getRecognitionRecoveryCount());
  }
}
