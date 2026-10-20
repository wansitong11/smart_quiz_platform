package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 试卷图片识别任务生产者：把「逐页识别」投递到 Redis Stream。
 */
@Slf4j
@Component
public class PaperRecognitionStreamProducer
    extends AbstractStreamProducer<PaperRecognitionStreamProducer.RecognitionTaskPayload> {

  private final ResumeRepository resumeRepository;

  record RecognitionTaskPayload(Long paperId) {
  }

  public PaperRecognitionStreamProducer(RedisService redisService, ResumeRepository resumeRepository) {
    super(redisService);
    this.resumeRepository = resumeRepository;
  }

  public boolean sendRecognitionTask(Long paperId) {
    return sendTask(new RecognitionTaskPayload(paperId));
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
  protected Map<String, String> buildMessage(RecognitionTaskPayload payload) {
    return Map.of(
        AsyncTaskStreamConstants.FIELD_PAPER_ID, payload.paperId().toString(),
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
    );
  }

  @Override
  protected String payloadIdentifier(RecognitionTaskPayload payload) {
    return "paperId=" + payload.paperId();
  }

  @Override
  protected void onSendFailed(RecognitionTaskPayload payload, String error) {
    resumeRepository.failRecognitionIfPending(payload.paperId(), truncateError(error), LocalDateTime.now());
  }
}
