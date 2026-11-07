package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.StreamWorkerRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.log.PaperFlowLog;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.PaperRecognitionService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 试卷图片识别消费者：逐页识别，状态与心跳复用切题任务的异步骨架。
 */
@Slf4j
@Component
public class PaperRecognitionStreamConsumer
    extends AbstractStreamConsumer<PaperRecognitionStreamConsumer.PaperRecognitionPayload> {

  private final PaperRecognitionService recognitionService;
  private final ResumeRepository resumeRepository;

  public PaperRecognitionStreamConsumer(
      RedisService redisService,
      StreamWorkerRegistry workerRegistry,
      PaperRecognitionService recognitionService,
      ResumeRepository resumeRepository
  ) {
    super(redisService, workerRegistry);
    this.recognitionService = recognitionService;
    this.resumeRepository = resumeRepository;
  }

  static final class PaperRecognitionPayload {

    private final Long paperId;
    private String attemptId;

    PaperRecognitionPayload(Long paperId) {
      this.paperId = paperId;
    }

    Long paperId() {
      return paperId;
    }

    String attemptId() {
      return attemptId;
    }

    void setAttemptId(String attemptId) {
      this.attemptId = attemptId;
    }
  }

  @Override
  protected String taskDisplayName() {
    return "试卷识别";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.PAPER_RECOGNITION_STREAM_KEY;
  }

  @Override
  protected String groupName() {
    return AsyncTaskStreamConstants.PAPER_RECOGNITION_GROUP_NAME;
  }

  @Override
  protected String consumerPrefix() {
    return AsyncTaskStreamConstants.PAPER_RECOGNITION_CONSUMER_PREFIX;
  }

  @Override
  protected String consumerKey() {
    return "paper-recognition";
  }

  @Override
  protected String threadName() {
    return "paper-recognition-consumer";
  }

  @Override
  protected PaperRecognitionPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String paperId = data.get(AsyncTaskStreamConstants.FIELD_PAPER_ID);
    if (paperId == null) {
      PaperFlowLog.warn("PAPER_RECOGNITION_MESSAGE_INVALID messageId={}, fields={}",
          messageId, data == null ? null : data.keySet());
      log.warn("识别消息格式错误，跳过: messageId={}", messageId);
      return null;
    }
    try {
      PaperRecognitionPayload payload = new PaperRecognitionPayload(Long.parseLong(paperId));
      PaperFlowLog.event("PAPER_RECOGNITION_MESSAGE_PARSED messageId={}, paperId={}, fields={}",
          messageId, payload.paperId(), data);
      return payload;
    } catch (NumberFormatException e) {
      PaperFlowLog.error("PAPER_RECOGNITION_MESSAGE_PARSE_FAILED messageId={}, paperId={}",
          e, messageId, paperId);
      throw e;
    }
  }

  @Override
  protected String payloadIdentifier(PaperRecognitionPayload payload) {
    return "paperId=" + payload.paperId();
  }

  @Override
  protected boolean shouldSkip(PaperRecognitionPayload payload) {
    return resumeRepository.findById(payload.paperId())
        .map(paper -> paper.getRecognitionStatus() != RecognitionStatus.PENDING)
        .orElse(true);
  }

  @Override
  protected void markProcessing(PaperRecognitionPayload payload) {
    // 条件领取模式下不使用
  }

  @Override
  protected boolean tryMarkProcessing(PaperRecognitionPayload payload) {
    String attemptId = UUID.randomUUID().toString();
    boolean claimed = resumeRepository.tryMarkRecognitionProcessing(
        payload.paperId(), attemptId, LocalDateTime.now()) == 1;
    if (claimed) {
      payload.setAttemptId(attemptId);
    }
    PaperFlowLog.event("PAPER_RECOGNITION_CLAIM paperId={}, attemptId={}, claimed={}",
        payload.paperId(), attemptId, claimed);
    return claimed;
  }

  @Override
  protected void processBusiness(PaperRecognitionPayload payload) {
    long startNanos = System.nanoTime();
    try (PaperFlowLog.Scope ignored =
             PaperFlowLog.open("PAPER_RECOGNITION", payload.paperId(), payload.attemptId(), null)) {
      PaperFlowLog.event("PAPER_RECOGNITION_CONSUMER_BEGIN attemptId={}", payload.attemptId());
      recognitionService.processPaper(payload.paperId(), payload.attemptId());
      PaperFlowLog.event("PAPER_RECOGNITION_CONSUMER_END status=SUCCESS, durationMs={}",
          elapsedMs(startNanos));
    } catch (RuntimeException e) {
      PaperFlowLog.error("PAPER_RECOGNITION_CONSUMER_END status=FAILED, durationMs={}",
          e, elapsedMs(startNanos));
      throw e;
    }
  }

  @Override
  protected void markCompleted(PaperRecognitionPayload payload) {
    int updated = resumeRepository.completeRecognitionIfProcessing(
        payload.paperId(), payload.attemptId(), LocalDateTime.now());
    PaperFlowLog.event("PAPER_RECOGNITION_COMPLETE paperId={}, attemptId={}, updated={}",
        payload.paperId(), payload.attemptId(), updated);
  }

  @Override
  protected void markFailed(PaperRecognitionPayload payload, String error) {
    int updated = resumeRepository.failRecognitionIfProcessing(
        payload.paperId(), payload.attemptId(), truncateError(error), LocalDateTime.now());
    PaperFlowLog.warn("PAPER_RECOGNITION_FAILED paperId={}, attemptId={}, updated={}, error={}",
        payload.paperId(), payload.attemptId(), updated, error);
  }

  @Override
  protected void retryMessage(PaperRecognitionPayload payload, int retryCount) {
    int reset = resumeRepository.resetRecognitionToPending(
        payload.paperId(), payload.attemptId(), LocalDateTime.now());
    if (reset != 1) {
      PaperFlowLog.warn("PAPER_RECOGNITION_RETRY_RESET_IGNORED paperId={}, attemptId={}, "
              + "retryCount={}, updated={}",
          payload.paperId(), payload.attemptId(), retryCount, reset);
      log.warn("识别重试重置失败，ACK 丢弃: paperId={}, retryCount={}",
          payload.paperId(), retryCount);
      return;
    }
    try {
      Map<String, String> message = new LinkedHashMap<>();
      message.put(AsyncTaskStreamConstants.FIELD_PAPER_ID, payload.paperId().toString());
      message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
      message.putAll(aiContextMessageFields());
      redisService().streamAdd(
          AsyncTaskStreamConstants.PAPER_RECOGNITION_STREAM_KEY,
          message,
          AsyncTaskStreamConstants.STREAM_MAX_LEN
      );
      PaperFlowLog.event("PAPER_RECOGNITION_RETRY_ENQUEUED paperId={}, attemptId={}, "
              + "retryCount={}",
          payload.paperId(), payload.attemptId(), retryCount);
    } catch (Exception e) {
      PaperFlowLog.error("PAPER_RECOGNITION_RETRY_ENQUEUE_FAILED paperId={}, attemptId={}, "
              + "retryCount={}",
          e, payload.paperId(), payload.attemptId(), retryCount);
      resumeRepository.failRecognitionIfPending(payload.paperId(), "重试入队失败", LocalDateTime.now());
    }
  }

  private long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  @Override
  protected void logTaskFailure(PaperRecognitionPayload payload, Exception error) {
    PaperFlowLog.error("PAPER_RECOGNITION_TASK_FAILED paperId={}, attemptId={}",
        error, payload.paperId(), payload.attemptId());
  }
}
