package interview.guide.modules.resume.listener;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.log.PaperFlowLog;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
        PaperFlowLog.event("PAPER_EXTRACT_PRODUCER_REQUEST paperId={}", paperId);
        boolean sent = sendTask(new PaperTaskPayload(paperId));
        PaperFlowLog.event("PAPER_EXTRACT_PRODUCER_RESULT paperId={}, sent={}", paperId, sent);
        return sent;
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
        Map<String, String> message = new LinkedHashMap<>();
        message.put(AsyncTaskStreamConstants.FIELD_PAPER_ID, payload.paperId().toString());
        message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");

        AiCallContext context = AiCallContextHolder.get();
        if (context == null || context.userId() == null) {
            resumeRepository.findOwnerIdById(payload.paperId())
                .filter(Objects::nonNull)
                .ifPresentOrElse(
                    ownerId -> message.put(
                        AsyncTaskStreamConstants.FIELD_OWNER_ID, ownerId.toString()),
                    () -> PaperFlowLog.warn(
                        "PAPER_EXTRACT_PRODUCER_OWNER_MISSING paperId={}", payload.paperId()));
        }
        if (context == null || context.feature() == null) {
            message.put(AsyncTaskStreamConstants.FIELD_AI_FEATURE,
                AiFeature.PAPER_EXTRACTION.name());
        }
        return message;
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

    @Override
    protected void logSendFailure(PaperTaskPayload payload, Exception error) {
        PaperFlowLog.error("PAPER_EXTRACT_PRODUCER_FAILED paperId={}", error, payload.paperId());
    }
}
