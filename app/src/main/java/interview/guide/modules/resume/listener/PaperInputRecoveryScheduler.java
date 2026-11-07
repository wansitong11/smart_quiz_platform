package interview.guide.modules.resume.listener;

import interview.guide.common.async.recovery.PaperInputRecoveryProperties;
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
 * 试卷输入处理卡住任务恢复调度器。
 */
@Slf4j
@Component
public class PaperInputRecoveryScheduler {

    private final ResumeRepository resumeRepository;
    private final PaperInputStreamProducer producer;
    private final PaperInputRecoveryProperties properties;

    public PaperInputRecoveryScheduler(ResumeRepository resumeRepository,
                                       PaperInputStreamProducer producer,
                                       PaperInputRecoveryProperties properties) {
        this.resumeRepository = resumeRepository;
        this.producer = producer;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.async.recovery.paper-input.interval-ms:60000}",
        initialDelayString = "${app.async.recovery.paper-input.interval-ms:60000}")
    public void recoverStuckTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<PracticePaperEntity> stalePending = resumeRepository.findStaleByInputStatus(
            AsyncTaskStatus.PENDING, now.minus(properties.getPendingThreshold()),
            PageRequest.of(0, properties.getBatchSize()));
        for (PracticePaperEntity paper : stalePending) {
            recover(paper.getId(), true,
                now.minus(properties.getPendingThreshold()), now);
        }
        List<PracticePaperEntity> staleProcessing = resumeRepository.findStaleByInputStatus(
            AsyncTaskStatus.PROCESSING, now.minus(properties.getProcessingThreshold()),
            PageRequest.of(0, properties.getBatchSize()));
        for (PracticePaperEntity paper : staleProcessing) {
            int reset = resumeRepository.resetInputToPending(
                paper.getId(), paper.getInputAttemptId(), now);
            if (reset == 1) {
                recover(paper.getId(), false, now, now);
            }
        }
    }

    private void recover(Long paperId, boolean alreadyPending,
                         LocalDateTime threshold, LocalDateTime now) {
        if (alreadyPending) {
            int touched = resumeRepository.touchQueuedInputForRecovery(paperId, threshold, now);
            if (touched != 1) {
                return;
            }
        }
        PracticePaperEntity paper = resumeRepository.findById(paperId).orElse(null);
        if (paper == null) {
            return;
        }
        int nextCount = paper.getInputRecoveryCount() + 1;
        if (nextCount >= properties.getMaxRecoveryCount()) {
            resumeRepository.incrementInputRecoveryCount(paperId);
            resumeRepository.failInputIfPending(
                paperId,
                "自动恢复次数达到上限（" + nextCount + "），请手动重试",
                LocalDateTime.now());
            log.warn("试卷输入处理自动恢复达到上限: paperId={}, recoveryCount={}",
                paperId, nextCount);
            return;
        }
        resumeRepository.incrementInputRecoveryCount(paperId);
        producer.sendInputTask(paperId);
    }
}
