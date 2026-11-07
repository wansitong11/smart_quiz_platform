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
 * 试卷输入处理任务生产者：把「原始文件解析」投递到 Redis Stream。
 */
@Slf4j
@Component
public class PaperInputStreamProducer
    extends AbstractStreamProducer<PaperInputStreamProducer.InputTaskPayload> {

    private final ResumeRepository resumeRepository;

    record InputTaskPayload(Long paperId) {
    }

    public PaperInputStreamProducer(RedisService redisService, ResumeRepository resumeRepository) {
        super(redisService);
        this.resumeRepository = resumeRepository;
    }

    public boolean sendInputTask(Long paperId) {
        return sendTask(new InputTaskPayload(paperId));
    }

    @Override
    protected String taskDisplayName() {
        return "试卷输入处理";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.PAPER_INPUT_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(InputTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_PAPER_ID, payload.paperId().toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(InputTaskPayload payload) {
        return "paperId=" + payload.paperId();
    }

    @Override
    protected void onSendFailed(InputTaskPayload payload, String error) {
        resumeRepository.failInputIfPending(
            payload.paperId(), truncateError(error), LocalDateTime.now());
    }
}
