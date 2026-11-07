package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.ExecutorRejectedException;
import interview.guide.common.ai.VisionRequestRejectedException;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.log.PaperFlowLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 单卷内页级识别协调器。
 *
 * <p>Redis 仍按整卷领取；这里只负责把同一份卷的图片页提交到有界执行器，
 * 主线程负责心跳、超时和页级终态收敛。</p>
 */
@Slf4j
@Component
public class PaperPageRecognitionProcessor {

  private final PaperPageRepository pageRepository;
  private final PaperRecognitionStateService recognitionStateService;
  private final PaperAssetService assetService;
  private final BoundedExecutor executor;
  private final PaperRecognitionExecutorProperties properties;
  private final ObjectMapper objectMapper;

  public PaperPageRecognitionProcessor(
      PaperPageRepository pageRepository,
      PaperRecognitionStateService recognitionStateService,
      PaperAssetService assetService,
      @Qualifier("paperRecognitionExecutor")
      BoundedExecutor executor,
      PaperRecognitionExecutorProperties properties,
      ObjectMapper objectMapper
  ) {
    this.pageRepository = pageRepository;
    this.recognitionStateService = recognitionStateService;
    this.assetService = assetService;
    this.executor = executor;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  /**
   * 只重置超时仍处于 PROCESSING 的页；READY / FAILED 永不被恢复扫描覆盖。
   */
  public void resetStalePages(Long paperId) {
    LocalDateTime now = LocalDateTime.now();
    int reset = pageRepository.resetStaleProcessingPages(
        paperId, now.minus(properties.getPageStaleThreshold()), now);
    PaperFlowLog.event("PAGE_STALE_SCAN threshold={}, reset={}",
        properties.getPageStaleThreshold(), reset);
    if (reset > 0) {
      log.warn("重置超时未完成的试卷页: paperId={}, count={}", paperId, reset);
      PaperFlowLog.warn("PAGE_STALE_RESET count={}, threshold={}",
          reset, properties.getPageStaleThreshold());
    }
  }

  public void process(
      Long paperId,
      List<PaperPageEntity> pages,
      PageRecognizer recognizer,
      Runnable heartbeat
  ) {
    if (pages == null || pages.isEmpty()) {
      throw new BusinessException(
          ErrorCode.PAPER_RECOGNITION_FAILED, "试卷没有可识别的页");
    }
    long startNanos = System.nanoTime();
    try (PaperFlowLog.Scope ignored =
             PaperFlowLog.open("PAGE_RECOGNITION", paperId, null, null)) {
      PaperFlowLog.event("PAGE_RECOGNITION_BEGIN pages={}, states={}",
          pages.size(), pageStates(pages));
      processTextPages(paperId, pages);
      processImagePages(paperId, pages, recognizer, heartbeat);
      ensureConverged(paperId);
      PaperFlowLog.event("PAGE_RECOGNITION_END status=SUCCESS, durationMs={}",
          elapsedMs(startNanos));
    } catch (RuntimeException e) {
      PaperFlowLog.error("PAGE_RECOGNITION_END status=FAILED, durationMs={}",
          e, elapsedMs(startNanos));
      throw e;
    }
  }

  /**
   * 全部页失败时沿用原有整卷失败路径；单页失败不在这里抛整卷异常。
   */
  public void failIfAllPagesFailed(Long paperId) {
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
    PaperFlowLog.event("ALL_PAGES_FAILED_CHECK states={}", pageStates(pages));
    if (!pages.isEmpty()
        && pages.stream().allMatch(page -> page.getStatus() == PageStatus.FAILED)) {
      throw new BusinessException(
          ErrorCode.PAPER_RECOGNITION_FAILED,
          "所有页均识别失败，请检查文件清晰度或识图模型配置");
    }
  }

  private void processTextPages(Long paperId, List<PaperPageEntity> pages) {
    for (PaperPageEntity page : pages) {
      if (page.getSource() != PageSource.TEXT || page.getStatus() != PageStatus.PENDING) {
        continue;
      }
      String attemptId = UUID.randomUUID().toString();
      if (pageRepository.claimPage(paperId, page.getPageNo(), attemptId, LocalDateTime.now())
          != 1) {
        PaperFlowLog.warn("TEXT_PAGE_CLAIM_SKIPPED pageNo={}, status={}, attempt={}",
            page.getPageNo(), page.getStatus(), page.getAttempt());
        continue;
      }
      PaperFlowLog.event("TEXT_PAGE_READY pageNo={}, attemptId={}, chars={}",
          page.getPageNo(), attemptId,
          page.getTextContent() == null ? 0 : page.getTextContent().length());
      markPageReady(page, attemptId, new RecognizedPage(
          page.getTextContent(), false, null, page.getModel(),
          preservedTextGeometry(page)));
    }
  }

  private PageRecognitionGeometrySnapshot preservedTextGeometry(PaperPageEntity page) {
    String assetsJson = page.getAssetsJson();
    if (assetsJson == null || assetsJson.isBlank()) {
      return PageRecognitionGeometrySnapshot.empty(page.getPageNo());
    }
    try {
      PageRecognitionGeometrySnapshot snapshot = objectMapper.readValue(
          assetsJson, PageRecognitionGeometrySnapshot.class);
      if (snapshot != null && snapshot.compatible()
          && snapshot.pageNo() == page.getPageNo()) {
        return snapshot;
      }
    } catch (JacksonException e) {
      PaperFlowLog.error("TEXT_PAGE_GEOMETRY_DECODE_FAILED pageNo={}, jsonChars={}",
          e, page.getPageNo(), assetsJson.length());
      log.warn("TEXT 页几何快照解析失败，重处理时使用空快照: paperId={}, pageNo={}",
          page.getPaperId(), page.getPageNo(), e);
    }
    return PageRecognitionGeometrySnapshot.empty(page.getPageNo());
  }

  private void processImagePages(
      Long paperId,
      List<PaperPageEntity> pages,
      PageRecognizer recognizer,
      Runnable heartbeat
  ) {
    List<PaperPageEntity> imagePages = pages.stream()
        .filter(page -> page.getSource() == PageSource.IMAGE)
        .filter(page -> page.getStatus() == PageStatus.PENDING)
        .toList();
    if (imagePages.isEmpty()) {
      PaperFlowLog.event("IMAGE_PAGES_EMPTY");
      return;
    }
    PaperFlowLog.event("IMAGE_PAGES_SUBMIT_BEGIN pages={}, pool={}, active={}, queue={}, "
            + "remainingCapacity={}",
        imagePages.stream().map(PaperPageEntity::getPageNo).toList(),
        executor.name(), executor.activeCount(), executor.queueSize(),
        executor.queueRemainingCapacity());

    CompletionService<Boolean> completionService = executor.completionService();
    List<SubmittedPage> submitted = new ArrayList<>();
    List<Future<Boolean>> futures = new ArrayList<>();
    Map<Future<Boolean>, Long> deadlines = new HashMap<>();
    long deadline = System.nanoTime() + properties.getPageStaleThreshold().toNanos();
    try {
      for (PaperPageEntity page : imagePages) {
        String attemptId = UUID.randomUUID().toString();
        if (pageRepository.claimPage(
            paperId, page.getPageNo(), attemptId, LocalDateTime.now()) != 1) {
          PaperFlowLog.warn("IMAGE_PAGE_CLAIM_SKIPPED pageNo={}, status={}, attempt={}",
              page.getPageNo(), page.getStatus(), page.getAttempt());
          continue;
        }
        SubmittedPage submittedPage = new SubmittedPage(page, attemptId);
        submitted.add(submittedPage);
        Future<Boolean> future = completionService.submit(
            () -> recognizePage(submittedPage, recognizer));
        futures.add(future);
        deadlines.put(future, deadline);
        PaperFlowLog.event("IMAGE_PAGE_SUBMITTED pageNo={}, attemptId={}",
            page.getPageNo(), attemptId);
      }

      collectResults(
          completionService, deadlines, heartbeat, deadline);
    } catch (ExecutorRejectedException e) {
      PaperFlowLog.error("IMAGE_PAGE_SUBMIT_REJECTED executor={}, submitted={}, active={}, queue={}",
          e, executor.name(), submitted.size(), executor.activeCount(), executor.queueSize());
      cancel(futures);
      releaseClaims(paperId, submitted);
      throw e;
    } catch (VisionRequestRejectedException e) {
      PaperFlowLog.error("IMAGE_PAGE_VISION_REJECTED submitted={}, active={}, queue={}",
          e, submitted.size(), executor.activeCount(), executor.queueSize());
      cancel(futures);
      releaseClaims(paperId, submitted);
      throw e;
    } catch (RuntimeException e) {
      PaperFlowLog.error("IMAGE_PAGE_ORCHESTRATION_FAILED submitted={}",
          e, submitted.size());
      cancel(futures);
      releaseClaims(paperId, submitted);
      throw e;
    }
  }

  private void cancel(List<Future<Boolean>> futures) {
    futures.forEach(future -> future.cancel(true));
  }

  private void collectResults(
      CompletionService<Boolean> completionService,
      Map<Future<Boolean>, Long> deadlines,
      Runnable heartbeat,
      long deadline
  ) {
    int remaining = deadlines.size();
    long heartbeatNanos = properties.getHeartbeatInterval().toNanos();
    while (remaining > 0) {
      try {
        long now = System.nanoTime();
        if (now >= deadline) {
          BusinessException timeout = new BusinessException(
              ErrorCode.INTERNAL_ERROR, "试卷页识别执行超时，请稍后重试");
          PaperFlowLog.error("PAGE_RECOGNITION_TIMEOUT remaining={}, timeoutMs={}",
              timeout, remaining, properties.getPageStaleThreshold().toMillis());
          throw timeout;
        }
        long waitNanos = Math.min(
            Math.max(1, deadline - now),
            Math.max(1, heartbeatNanos));
        Future<Boolean> future = completionService.poll(
            waitNanos, TimeUnit.NANOSECONDS);
        heartbeat.run();
        if (future == null) {
          continue;
        }
        deadlines.remove(future);
        try {
          future.get();
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          PaperFlowLog.error("PAGE_WORKER_FUTURE_FAILED remaining={}, cause={}",
              cause, remaining, cause == null ? null : cause.getClass().getName());
          if (cause instanceof VisionRequestRejectedException rejected) {
            throw rejected;
          }
          throw new BusinessException(
              ErrorCode.INTERNAL_ERROR,
              "试卷页识别任务异常，请稍后重试",
              cause);
        }
        remaining--;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        PaperFlowLog.error("PAGE_RECOGNITION_INTERRUPTED remaining={}", e, remaining);
        throw new BusinessException(
            ErrorCode.INTERNAL_ERROR, "试卷页识别任务被中断，请稍后重试", e);
      }
    }
  }

  private boolean recognizePage(
      SubmittedPage submittedPage,
      PageRecognizer recognizer
  ) {
    PaperPageEntity page = submittedPage.page();
    String attemptId = submittedPage.attemptId();
    long startNanos = System.nanoTime();
    try (PaperFlowLog.Scope ignored = PaperFlowLog.open(
        "PAGE_RECOGNITION", page.getPaperId(), attemptId, page.getPageNo())) {
      PaperFlowLog.event("PAGE_WORKER_BEGIN source={}, status={}, imageKey={}, attempt={}",
          page.getSource(), page.getStatus(), page.getImageKey(), page.getAttempt());
      try {
        RecognizedPage recognized = recognizer.recognize(page, attemptId);
        markPageReady(page, attemptId, recognized);
        PaperFlowLog.event("PAGE_WORKER_END status=READY, durationMs={}, chars={}, hasFigure={}, "
                + "model={}",
            elapsedMs(startNanos), recognized.text() == null ? 0 : recognized.text().length(),
            recognized.hasFigure(), recognized.model());
        return true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        PaperFlowLog.error("PAGE_WORKER_END status=INTERRUPTED, durationMs={}",
            e, elapsedMs(startNanos));
        throw new BusinessException(
            ErrorCode.INTERNAL_ERROR, "试卷页识别被中断", e);
      } catch (VisionRequestRejectedException e) {
        PaperFlowLog.error("PAGE_WORKER_END status=VISION_REJECTED, durationMs={}",
            e, elapsedMs(startNanos));
        throw e;
      } catch (Exception e) {
        PaperFlowLog.error("PAGE_WORKER_END status=FAILED, durationMs={}, failureMessage={}",
            e, elapsedMs(startNanos), PaperRecognitionService.pageFailureMessage(e));
        markPageFailed(page, attemptId, e);
        cleanupAttemptAssets(page, attemptId);
        return false;
      }
    }
  }

  private void markPageReady(
      PaperPageEntity page,
      String attemptId,
      RecognizedPage recognized
  ) {
    String geometryJson = geometryJson(recognized);
    boolean updated = recognitionStateService.markPageReadyAndAdvanceRevision(
        page.getPaperId(),
        page.getPageNo(),
        attemptId,
        recognized.text() == null ? "" : recognized.text(),
        recognized.hasFigure(),
        truncate(recognized.notes(), 500),
        recognized.model(),
        geometryJson,
        LocalDateTime.now());
    PaperFlowLog.event("PAGE_STATE_WRITE target=READY pageNo={}, attemptId={}, updated={}, "
            + "chars={}, hasFigure={}, notes={}, model={}",
        page.getPageNo(), attemptId, updated,
        recognized.text() == null ? 0 : recognized.text().length(),
        recognized.hasFigure(), recognized.notes(), recognized.model());
    if (geometryJson != null) {
      PaperFlowLog.raw("PAGE_GEOMETRY_JSON pageNo=" + page.getPageNo(), geometryJson);
    }
    if (!updated) {
      log.info("忽略过期的页识别成功结果: paperId={}, pageNo={}, attemptId={}",
          page.getPaperId(), page.getPageNo(), attemptId);
      PaperFlowLog.warn("PAGE_STATE_WRITE_IGNORED target=READY pageNo={}, attemptId={}, updated={}",
          page.getPageNo(), attemptId, updated);
      cleanupAttemptAssets(page, attemptId);
    }
  }

  private void cleanupAttemptAssets(PaperPageEntity page, String attemptId) {
    try {
      assetService.deleteBySourceAttemptId(page.getPaperId(), attemptId);
    } catch (Exception cleanupError) {
      log.warn("清理失败识别代次资源失败: paperId={}, pageNo={}, attemptId={}",
          page.getPaperId(), page.getPageNo(), attemptId, cleanupError);
      PaperFlowLog.error("PAGE_ATTEMPT_ASSET_CLEANUP_FAILED pageNo={}, attemptId={}",
          cleanupError, page.getPageNo(), attemptId);
    }
  }

  private void markPageFailed(
      PaperPageEntity page,
      String attemptId,
      Exception error
  ) {
    int updated = pageRepository.markPageFailedIfClaimed(
        page.getPaperId(),
        page.getPageNo(),
        attemptId,
        truncate(PaperRecognitionService.pageFailureMessage(error), 500),
        LocalDateTime.now());
    PaperFlowLog.event("PAGE_STATE_WRITE target=FAILED pageNo={}, attemptId={}, updated={}, "
            + "userMessage={}, errorType={}",
        page.getPageNo(), attemptId, updated,
        truncate(PaperRecognitionService.pageFailureMessage(error), 500),
        error.getClass().getName());
    if (updated != 1) {
      log.info("忽略过期的页识别失败结果: paperId={}, pageNo={}, attemptId={}",
          page.getPaperId(), page.getPageNo(), attemptId);
      PaperFlowLog.warn("PAGE_STATE_WRITE_IGNORED target=FAILED pageNo={}, attemptId={}, updated={}",
          page.getPageNo(), attemptId, updated);
    }
  }

  private void releaseClaims(Long paperId, List<SubmittedPage> submitted) {
    if (submitted.isEmpty()) {
      return;
    }
    List<String> attemptIds = submitted.stream()
        .map(SubmittedPage::attemptId)
        .distinct()
        .toList();
    int reset = pageRepository.resetClaimedPagesToPending(paperId, attemptIds, LocalDateTime.now());
    PaperFlowLog.warn("PAGE_CLAIMS_RELEASED attempts={}, updated={}", attemptIds, reset);
  }

  private void ensureConverged(Long paperId) {
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
    PaperFlowLog.event("PAGE_CONVERGENCE_CHECK states={}", pageStates(pages));
    boolean stillInProgress = pages.stream()
        .anyMatch(page -> page.getStatus() == PageStatus.PENDING
            || page.getStatus() == PageStatus.PROCESSING);
    if (stillInProgress) {
      BusinessException convergenceError = new BusinessException(
          ErrorCode.INTERNAL_ERROR, "试卷页识别状态未收敛，请稍后重试");
      PaperFlowLog.error("PAGE_CONVERGENCE_FAILED states={}",
          convergenceError, pageStates(pages));
      throw convergenceError;
    }
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private List<String> pageStates(List<PaperPageEntity> pages) {
    if (pages == null) {
      return List.of();
    }
    return pages.stream()
        .map(page -> "page=" + page.getPageNo()
            + ",source=" + page.getSource()
            + ",status=" + page.getStatus()
            + ",attempt=" + page.getAttempt()
            + ",attemptId=" + page.getRecognitionAttemptId())
        .toList();
  }

  private long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  @FunctionalInterface
  public interface PageRecognizer {

    RecognizedPage recognize(PaperPageEntity page, String attemptId) throws Exception;
  }

  public record RecognizedPage(
      String text,
      boolean hasFigure,
      String notes,
      String model,
      PageRecognitionGeometrySnapshot geometry
  ) {
    public RecognizedPage(String text, boolean hasFigure, String notes, String model) {
      this(text, hasFigure, notes, model, null);
    }
  }

  private String geometryJson(RecognizedPage recognized) {
    if (recognized.geometry() == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(recognized.geometry());
    } catch (JacksonException e) {
      throw new BusinessException(
          ErrorCode.PAPER_RECOGNITION_FAILED, "页几何快照序列化失败", e);
    }
  }

  private record SubmittedPage(PaperPageEntity page, String attemptId) {
  }
}
