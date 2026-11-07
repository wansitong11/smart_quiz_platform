package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.StreamWorkerRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.PaperInputProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 试卷输入处理消费者：分发线程领取消息，Stream worker 并行解析不同试卷。
 */
@Slf4j
@Component
public class PaperInputStreamConsumer
    extends AbstractStreamConsumer<PaperInputStreamConsumer.PaperInputPayload> {

    private final ResumeRepository resumeRepository;
    private final PaperInputProcessingService inputProcessingService;

    public PaperInputStreamConsumer(
        RedisService redisService,
        StreamWorkerRegistry workerRegistry,
        ResumeRepository resumeRepository,
        PaperInputProcessingService inputProcessingService
    ) {
        super(redisService, workerRegistry);
        this.resumeRepository = resumeRepository;
        this.inputProcessingService = inputProcessingService;
    }

    static final class PaperInputPayload {

        private final Long paperId;
        private final int retryCount;
        private String attemptId;

        PaperInputPayload(Long paperId, int retryCount) {
            this.paperId = paperId;
            this.retryCount = retryCount;
        }

        Long paperId() {
            return paperId;
        }

        int retryCount() {
            return retryCount;
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
        return "试卷输入处理";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.PAPER_INPUT_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.PAPER_INPUT_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.PAPER_INPUT_CONSUMER_PREFIX;
    }

    @Override
    protected String consumerKey() {
        return "paper-input";
    }

    @Override
    protected String threadName() {
        return "paper-input-consumer";
    }

    @Override
    protected PaperInputPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String paperId = data.get(AsyncTaskStreamConstants.FIELD_PAPER_ID);
        if (paperId == null || paperId.isBlank()) {
            log.warn("试卷输入处理消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new PaperInputPayload(Long.parseLong(paperId), parseRetryCount(data));
    }

    @Override
    protected String payloadIdentifier(PaperInputPayload payload) {
        return "paperId=" + payload.paperId();
    }

    @Override
    protected boolean shouldSkip(PaperInputPayload payload) {
        return resumeRepository.findById(payload.paperId())
            .map(paper -> paper.getInputStatus() == AsyncTaskStatus.COMPLETED)
            .orElse(true);
    }

    @Override
    protected void markProcessing(PaperInputPayload payload) {
        // 条件领取模式下不使用
    }

    @Override
    protected boolean tryMarkProcessing(PaperInputPayload payload) {
        String attemptId = UUID.randomUUID().toString();
        boolean claimed = resumeRepository.tryMarkInputProcessing(
            payload.paperId(), attemptId, LocalDateTime.now()) == 1;
        if (claimed) {
            payload.setAttemptId(attemptId);
        }
        return claimed;
    }

    @Override
    protected void processBusiness(PaperInputPayload payload) {
        inputProcessingService.process(payload.paperId(), payload.attemptId());
    }

    @Override
    protected boolean shouldFinalizeBudgetOnSuccess() {
        // 输入解析只是上传链路中间阶段，后续仍会投递识图或切题任务；
        // 同一个操作号必须保留预占，等最终阶段结算真实成本。
        return false;
    }

    @Override
    protected void markCompleted(PaperInputPayload payload) {
        resumeRepository.completeInputIfProcessing(
            payload.paperId(), payload.attemptId(), LocalDateTime.now());
    }

    @Override
    protected void markFailed(PaperInputPayload payload, String error) {
        resumeRepository.failInputIfProcessing(
            payload.paperId(), payload.attemptId(), truncateError(error), LocalDateTime.now());
    }

    @Override
    protected void retryMessage(PaperInputPayload payload, int retryCount) {
        int reset = resumeRepository.resetInputToPending(
            payload.paperId(), payload.attemptId(), LocalDateTime.now());
        if (reset != 1) {
            log.warn("输入处理重试重置失败，ACK 丢弃: paperId={}, retryCount={}",
                payload.paperId(), retryCount);
            return;
        }
        try {
            Map<String, String> message = new LinkedHashMap<>();
            message.put(AsyncTaskStreamConstants.FIELD_PAPER_ID, payload.paperId().toString());
            message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
            message.putAll(aiContextMessageFields());
            redisService().streamAdd(
                AsyncTaskStreamConstants.PAPER_INPUT_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
        } catch (Exception e) {
            resumeRepository.failInputIfPending(
                payload.paperId(), "重试入队失败", LocalDateTime.now());
        }
    }
}
