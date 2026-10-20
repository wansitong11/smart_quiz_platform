package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.PaperRecognitionService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
      PaperRecognitionService recognitionService,
      ResumeRepository resumeRepository
  ) {
    super(redisService);
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
  protected String threadName() {
    return "paper-recognition-consumer";
  }

  @Override
  protected PaperRecognitionPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String paperId = data.get(AsyncTaskStreamConstants.FIELD_PAPER_ID);
    if (paperId == null) {
      log.warn("识别消息格式错误，跳过: messageId={}", messageId);
      return null;
    }
    return new PaperRecognitionPayload(Long.parseLong(paperId));
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
    return claimed;
  }

  @Override
  protected void processBusiness(PaperRecognitionPayload payload) {
    recognitionService.processPaper(payload.paperId(), payload.attemptId());
  }

  @Override
  protected void markCompleted(PaperRecognitionPayload payload) {
    resumeRepository.completeRecognitionIfProcessing(
        payload.paperId(), payload.attemptId(), LocalDateTime.now());
  }

  @Override
  protected void markFailed(PaperRecognitionPayload payload, String error) {
    resumeRepository.failRecognitionIfProcessing(
        payload.paperId(), payload.attemptId(), truncateError(error), LocalDateTime.now());
  }

  @Override
  protected void retryMessage(PaperRecognitionPayload payload, int retryCount) {
    int reset = resumeRepository.resetRecognitionToPending(
        payload.paperId(), payload.attemptId(), LocalDateTime.now());
    if (reset != 1) {
      log.warn("识别重试重置失败，ACK 丢弃: paperId={}, retryCount={}",
          payload.paperId(), retryCount);
      return;
    }
    try {
      redisService().streamAdd(
          AsyncTaskStreamConstants.PAPER_RECOGNITION_STREAM_KEY,
          Map.of(
              AsyncTaskStreamConstants.FIELD_PAPER_ID, payload.paperId().toString(),
              AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
          ),
          AsyncTaskStreamConstants.STREAM_MAX_LEN
      );
    } catch (Exception e) {
      log.error("识别重试入队失败: paperId={}, error={}", payload.paperId(),
          ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
      resumeRepository.failRecognitionIfPending(payload.paperId(), "重试入队失败", LocalDateTime.now());
    }
  }
}
