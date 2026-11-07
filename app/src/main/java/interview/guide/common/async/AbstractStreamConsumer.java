package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.ai.AiErrorMessages;
import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiCostControl;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.ai.RetryableFailureClassifier;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final StreamWorkerRegistry workerRegistry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private StreamWorkerPool workerPool;
    private String consumerName;
    private AiCostControl aiCostControl;

    protected AbstractStreamConsumer(
        RedisService redisService,
        StreamWorkerRegistry workerRegistry
    ) {
        this.redisService = redisService;
        this.workerRegistry = workerRegistry;
    }

    @Autowired(required = false)
    public void setAiCostControl(AiCostControl aiCostControl) {
        this.aiCostControl = aiCostControl;
    }

    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        this.workerPool = workerRegistry.acquire(consumerKey(), streamKey(), groupName());
        this.executorService = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, threadName());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        running.set(true);
        executorService.submit(this::startConsumer);
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (workerPool != null) {
            try {
                if (!workerPool.awaitIdle(workerPool.drainTimeout())) {
                    workerPool.stopAcking();
                    log.warn("{} worker drain timeout, unfinished messages stay pending: "
                            + "consumerName={}, inFlight={}",
                        taskDisplayName(), consumerName, workerPool.inFlight());
                }
            } catch (Exception e) {
                log.warn("{} worker drain failed: consumerName={}",
                    taskDisplayName(), consumerName, e);
            }
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    private void startConsumer() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        consumeLoop();
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,
                    AsyncTaskStreamConstants.PENDING_CLAIM_BATCH_SIZE,
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }

    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload;
        try {
            payload = parsePayload(messageId, data);
        } catch (Exception e) {
            Object fields = data == null ? null : data.keySet();
            log.warn("Failed to parse {} stream message, ack and discard: messageId={}, fields={}",
                taskDisplayName(), messageId, fields, e);
            ackMessage(messageId);
            return;
        }

        if (payload == null) {
            ackMessage(messageId);
            return;
        }

        int retryCount = parseRetryCount(data);
        AiCallContext callContext = parseCallContext(data);
        StreamMessageLease lease = new StreamMessageLease(
            redisService,
            workerPool,
            streamKey(),
            groupName(),
            messageId
        );
        boolean submitted = workerPool.submitBlocking(
            () -> executeWorker(lease, payload, retryCount, callContext),
            running::get
        );
        if (!submitted) {
            log.info("{} task was not submitted, message stays pending: payload={}, messageId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId);
            return;
        }
        log.info("{} task dispatched: payload={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);
    }

    private void executeWorker(StreamMessageLease lease, T payload, int retryCount,
        AiCallContext callContext) {
        if (callContext != null) {
            AiCallContextHolder.set(callContext);
        }
        boolean safeToAck = false;
        boolean retryScheduled = false;
        boolean finalizeBudget = true;
        boolean completed = false;
        try {
            if (shouldSkip(payload)) {
                safeToAck = true;
                log.info("{} task skipped: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            if (!tryMarkProcessing(payload)) {
                safeToAck = true;
                log.info("{} task was not claimed: {}",
                    taskDisplayName(), payloadIdentifier(payload));
                return;
            }

            processBusiness(payload);
            markCompleted(payload);
            completed = true;
            workerPool.recordCompleted();
            safeToAck = true;
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            try {
                retryScheduled = handleTaskFailure(payload, retryCount, e);
                safeToAck = true;
            } catch (Exception stateError) {
                // 消息未 ACK、后续还会重投，预占必须保留到下一次处理结束。
                finalizeBudget = false;
                log.error("写入任务终态失败，保留消息 Pending: payload={}",
                    payloadIdentifier(payload), stateError);
            }
        } finally {
            if (finalizeBudget && !retryScheduled && aiCostControl != null && callContext != null
                && callContext.operationId() != null
                && (!completed || shouldFinalizeBudgetOnSuccess())) {
                try {
                    aiCostControl.finalize(callContext.operationId());
                } catch (Exception e) {
                    log.warn("{} task budget finalize failed: operationId={}",
                        taskDisplayName(), callContext.operationId(), e);
                }
            }
            if (callContext != null) {
                AiCallContextHolder.clear();
            }
            if (safeToAck) {
                lease.ack();
            }
        }
    }

    /**
     * 当前线程成功处理消息后，是否由本阶段结算操作预算。
     *
     * <p>如果本阶段只是上传、解析等中间步骤，后面还会投递识别或切题任务，应返回
     * {@code false}，让同一操作号的真实成本在最终阶段统一结算。</p>
     */
    protected boolean shouldFinalizeBudgetOnSuccess() {
        return true;
    }

    private AiCallContext parseCallContext(Map<String, String> data) {
        if (data == null) {
            return null;
        }
        Long ownerId = null;
        try {
            ownerId = Long.valueOf(data.get(AsyncTaskStreamConstants.FIELD_OWNER_ID));
        } catch (Exception e) {
            ownerId = null;
        }
        AiFeature feature = parseFeature(data.get(AsyncTaskStreamConstants.FIELD_AI_FEATURE));
        String operation = data.get(AsyncTaskStreamConstants.FIELD_AI_OPERATION);
        String operationId = data.get(AsyncTaskStreamConstants.FIELD_AI_OPERATION_ID);
        String requestId = data.get(AsyncTaskStreamConstants.FIELD_AI_REQUEST_ID);
        if (ownerId == null && feature == null) {
            return null;
        }
        return new AiCallContext(ownerId, feature, operation, requestId, operationId, "ASYNC");
    }

    private AiFeature parseFeature(String value) {
        if (value == null) {
            return null;
        }
        try {
            return AiFeature.valueOf(value);
        } catch (IllegalArgumentException e) {
            return AiFeature.UNKNOWN;
        }
    }

    private boolean handleTaskFailure(T payload, int retryCount, Exception error) {
        logTaskFailure(payload, error);

        // 业务异常是确定性失败（文件没有文字层、素材缺失、参数不合法……），重试不会变好，
        // 直接落库真实原因，避免用户只看到一句"failed after retry 3"无从下手
        if (error instanceof BusinessException
            && !RetryableFailureClassifier.isRetryable(error)) {
            markFailed(payload, truncateError(describeFailure(error)));
            workerPool.recordFailed();
            return false;
        }

        if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
            retryMessage(payload, retryCount + 1);
            workerPool.recordRetried();
            return true;
        } else {
            markFailed(payload, truncateError(
                taskDisplayName() + " failed after retry " + retryCount + "：" + describeFailure(error)
            ));
            workerPool.recordFailed();
            return false;
        }
    }

    /**
     * 试卷等专用链路可覆盖此方法输出未截断异常链；默认保持其他模块原有脱敏行为。
     */
    protected void logTaskFailure(T payload, Exception error) {
        log.error("{} task failed: {}, error={}", taskDisplayName(), payloadIdentifier(payload),
            ErrorLogSanitizer.summarize(error), ErrorLogSanitizer.forLogging(error));
    }

    /**
     * 生成写入失败原因字段的简短描述，供界面直接展示。
     * <ul>
     *   <li>{@link BusinessException} 的 message 是本服务自己编写的，安全且诊断价值最高；</li>
     *   <li>AI 调用类异常用 {@link AiErrorMessages} 翻译成人话（额度不足 / 密钥失效 / 网络不通）；</li>
     *   <li>其余异常只保留类型名，避免把外部响应正文或用户输入回显到界面。</li>
     * </ul>
     */
    protected String describeFailure(Throwable error) {
        if (error instanceof BusinessException business
            && business.getMessage() != null && !business.getMessage().isBlank()) {
            return business.getMessage();
        }
        return AiErrorMessages.describe(error).orElseGet(() -> ErrorLogSanitizer.summarize(error));
    }

    protected int parseRetryCount(Map<String, String> data) {
        if (data == null) {
            return 0;
        }
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 重投/恢复消息时保留 AI 成本与限流归因，否则 pacer 读不到上下文会直接放行。
     */
    protected Map<String, String> aiContextMessageFields() {
        AiCallContext context = AiCallContextHolder.get();
        if (context == null) {
            return Map.of();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (context.userId() != null) {
            fields.put(AsyncTaskStreamConstants.FIELD_OWNER_ID,
                String.valueOf(context.userId()));
        }
        if (context.feature() != null) {
            fields.put(AsyncTaskStreamConstants.FIELD_AI_FEATURE,
                context.feature().name());
        }
        if (context.operation() != null) {
            fields.put(AsyncTaskStreamConstants.FIELD_AI_OPERATION,
                context.operation());
        }
        if (context.operationId() != null) {
            fields.put(AsyncTaskStreamConstants.FIELD_AI_OPERATION_ID,
                context.operationId());
        }
        if (context.requestId() != null) {
            fields.put(AsyncTaskStreamConstants.FIELD_AI_REQUEST_ID,
                context.requestId());
        }
        return fields;
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    private void ackMessage(StreamMessageId messageId) {
        if (workerPool != null && !workerPool.ackAllowed()) {
            log.warn("Consumer 已停止 ACK，消息保持 Pending: messageId={}", messageId);
            return;
        }
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            if (workerPool != null) {
                workerPool.recordAckFailure();
            }
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    protected RedisService redisService() {
        return redisService;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String consumerKey();

    protected abstract String threadName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected boolean shouldSkip(T payload) {
        return false;
    }

    protected abstract void markProcessing(T payload);

    /**
     * 尝试领取任务。默认保持原有消费者的状态更新语义。
     */
    protected boolean tryMarkProcessing(T payload) {
        markProcessing(payload);
        return true;
    }

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    protected abstract void retryMessage(T payload, int retryCount);
}
