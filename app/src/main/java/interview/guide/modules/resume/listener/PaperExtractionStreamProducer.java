package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 试卷切题任务生产者：把「结构化切题」任务投递到 Redis Stream。
 */
@Slf4j
@Component
public class PaperExtractionStreamProducer
    extends AbstractStreamProducer<PaperExtractionStreamProducer.PaperTaskPayload> {

    private final ResumeRepository resumeRepository;
    private final TransactionalExecutor transactionalExecutor;

    record PaperTaskPayload(Long paperId) {}

    public PaperExtractionStreamProducer(
        RedisService redisService,
        ResumeRepository resumeRepository,
        TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.resumeRepository = resumeRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 投递切题任务。
     *
     * @param paperId 试卷 ID
     */
    public boolean sendExtractTask(Long paperId) {
        return sendTask(new PaperTaskPayload(paperId));
    }

    @Override
    protected String taskDisplayName() {
        return "切题";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.PAPER_EXTRACT_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(PaperTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_PAPER_ID, payload.paperId().toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(PaperTaskPayload payload) {
        return "paperId=" + payload.paperId();
    }

    @Override
    protected void onSendFailed(PaperTaskPayload payload, String error) {
        // 条件更新：不覆盖已被其他路径改写的终态（如 COMPLETED）
        resumeRepository.failAnalyzeIfPending(
            payload.paperId(), truncateError(error), java.time.LocalDateTime.now());
    }
}
