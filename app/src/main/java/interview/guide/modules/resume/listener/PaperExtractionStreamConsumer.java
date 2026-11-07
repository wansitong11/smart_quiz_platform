package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.StreamWorkerRegistry;
import interview.guide.common.ai.RetryableFailureClassifier;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.VisionRequestRejectedException;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.PaperExtractionResult;
import interview.guide.modules.resume.service.AnswerKeyPreparationService;
import interview.guide.modules.resume.service.PaperTextPageBootstrapper;
import interview.guide.modules.resume.service.PaperStructureService;
import interview.guide.modules.resume.service.ResumeParseService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.resume.log.PaperFlowLog;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 试卷切题 Stream 消费者。
 * <p>
 * 业务步骤：取材料正文（缺失时从对象存储恢复）→ 结构化切题 → 生成 PAPER 来源的练习会话 →
 * 落库解析摘要。任务状态、心跳、fencing token 与卡死恢复全部复用原有异步骨架。
 * </p>
 */
@Slf4j
@Component
public class PaperExtractionStreamConsumer
    extends AbstractStreamConsumer<PaperExtractionStreamConsumer.PaperExtractPayload> {

    private final PaperTextPageBootstrapper textPageBootstrapper;
    private final InterviewSessionService interviewSessionService;
    private final ResumePersistenceService persistenceService;
    private final ResumeRepository resumeRepository;
    private final ResumeParseService parseService;
    private final PaperStructureService structureService;
    private final AnswerKeyPreparationService answerKeyPreparationService;

    public PaperExtractionStreamConsumer(
        RedisService redisService,
        StreamWorkerRegistry workerRegistry,
        PaperTextPageBootstrapper textPageBootstrapper,
        InterviewSessionService interviewSessionService,
        ResumePersistenceService persistenceService,
        ResumeRepository resumeRepository,
        ResumeParseService parseService,
        PaperStructureService structureService,
        AnswerKeyPreparationService answerKeyPreparationService
    ) {
        super(redisService, workerRegistry);
        this.textPageBootstrapper = textPageBootstrapper;
        this.interviewSessionService = interviewSessionService;
        this.persistenceService = persistenceService;
        this.resumeRepository = resumeRepository;
        this.parseService = parseService;
        this.structureService = structureService;
        this.answerKeyPreparationService = answerKeyPreparationService;
    }

    static final class PaperExtractPayload {

        private final Long paperId;
        private String attemptId;

        PaperExtractPayload(Long paperId) {
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
        return "试卷切题";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.PAPER_EXTRACT_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.PAPER_EXTRACT_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.PAPER_EXTRACT_CONSUMER_PREFIX;
    }

    @Override
    protected String consumerKey() {
        return "paper-extract";
    }

    @Override
    protected String threadName() {
        return "paper-extract-consumer";
    }

    @Override
    protected PaperExtractPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String paperIdStr = data.get(AsyncTaskStreamConstants.FIELD_PAPER_ID);
        if (paperIdStr == null) {
            PaperFlowLog.warn("PAPER_EXTRACT_MESSAGE_INVALID messageId={}, fields={}",
                messageId, data == null ? null : data.keySet());
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        // 正文以数据库实体为事实来源，消息里只传 ID
        try {
            PaperExtractPayload payload = new PaperExtractPayload(Long.parseLong(paperIdStr));
            PaperFlowLog.event("PAPER_EXTRACT_MESSAGE_PARSED messageId={}, paperId={}, fields={}",
                messageId, payload.paperId(), data);
            return payload;
        } catch (NumberFormatException e) {
            PaperFlowLog.error("PAPER_EXTRACT_MESSAGE_PARSE_FAILED messageId={}, paperId={}",
                e, messageId, paperIdStr);
            throw e;
        }
    }

    @Override
    protected String payloadIdentifier(PaperExtractPayload payload) {
        return "paperId=" + payload.paperId();
    }

    @Override
    protected boolean shouldSkip(PaperExtractPayload payload) {
        return resumeRepository.findById(payload.paperId())
            .map(paper -> paper.getAnalysisStatus() == AsyncTaskStatus.COMPLETED)
            .orElse(true);
    }

    /**
     * 条件领取模式下 markProcessing 不再使用。
     */
    @Override
    protected void markProcessing(PaperExtractPayload payload) {
        // tryMarkProcessing 原子领取
    }

    /**
     * 条件领取：只有 PENDING → PROCESSING 成功的消费者才执行任务（多实例防重复）。
     */
    @Override
    protected boolean tryMarkProcessing(PaperExtractPayload payload) {
        String attemptId = UUID.randomUUID().toString();
        boolean claimed = resumeRepository.tryMarkAnalyzeProcessing(
            payload.paperId(), attemptId, java.time.LocalDateTime.now()) == 1;
        if (claimed) {
            payload.setAttemptId(attemptId);
        }
        PaperFlowLog.event("PAPER_EXTRACT_CLAIM paperId={}, attemptId={}, claimed={}",
            payload.paperId(), attemptId, claimed);
        return claimed;
    }

    @Override
    protected void processBusiness(PaperExtractPayload payload) {
        Long paperId = payload.paperId();
        long startNanos = System.nanoTime();
        try (PaperFlowLog.Scope ignored =
                 PaperFlowLog.open("PAPER_EXTRACTION", paperId, payload.attemptId(), null)) {
            PaperFlowLog.event("PAPER_EXTRACTION_BEGIN attemptId={}", payload.attemptId());
            processExtractionBusiness(payload);
            PaperFlowLog.event("PAPER_EXTRACTION_END status=SUCCESS, durationMs={}",
                elapsedMs(startNanos));
        } catch (RuntimeException e) {
            PaperFlowLog.error("PAPER_EXTRACTION_END status=FAILED, durationMs={}",
                e, elapsedMs(startNanos));
            throw e;
        }
    }

    private void processExtractionBusiness(PaperExtractPayload payload) {
        Long paperId = payload.paperId();
        PracticePaperEntity paper = resumeRepository.findById(paperId).orElse(null);
        if (paper == null) {
            PaperFlowLog.warn("PAPER_EXTRACT_SKIPPED reason=PAPER_DELETED paperId={}",
                paperId);
            log.warn("试卷已被删除，跳过切题任务: paperId={}", paperId);
            return;
        }

        String paperText = paper.getResumeText();
        PaperFlowLog.event("PAPER_EXTRACT_STATE recognitionStatus={}, analysisStatus={}, "
                + "attemptId={}, textChars={}, pageCount={}, sourceType={}",
            paper.getRecognitionStatus(), paper.getAnalysisStatus(), payload.attemptId(),
            paperText == null ? 0 : paperText.length(), paper.getPageCount(),
            paper.getSourceType());
        if (isBlank(paperText)) {
            // 历史数据正文为空时，从对象存储恢复文本并以独立短事务回填后再切题
            PaperFlowLog.event("PAPER_EXTRACT_TEXT_MISSING storageKey={}, filename={}",
                paper.getStorageKey(), paper.getOriginalFilename());
            paperText = parseService.downloadAndParseContent(
                paper.getStorageKey(), paper.getOriginalFilename());
            PaperFlowLog.event("PAPER_EXTRACT_TEXT_RECOVERED chars={}",
                paperText == null ? 0 : paperText.length());
            heartbeat(payload);
            if (isBlank(paperText)) {
                throw new BusinessException(ErrorCode.PAPER_PARSE_FAILED, "无法获取试卷文本内容");
            }
            persistenceService.updateResumeText(paperId, paperText);
        }

        textPageBootstrapper.ensureReadyTextPage(paperId, paperText);
        PaperFlowLog.event("PAPER_EXTRACT_TEXT_PAGE_READY textChars={}",
            paperText.length());
        PaperExtractionResult result = extractStructure(paperId, () -> heartbeat(payload));
        // LLM 切题完成心跳（分批调用已结束，落库前确认执行权仍在自己手里）
        heartbeat(payload);
        if (resumeRepository.findById(paperId).isEmpty()) {
            PaperFlowLog.warn("PAPER_EXTRACT_SKIPPED reason=PAPER_DELETED_DURING_EXTRACTION paperId={}",
                paperId);
            log.warn("试卷在切题期间被删除，跳过保存结果: paperId={}", paperId);
            return;
        }

        List<InterviewQuestionDTO> questions = result.questions();
        InterviewSessionDTO session = interviewSessionService.createSessionFromQuestions(
            questions,
            result.paperTree(),
            null,
            result.subjectId(),
            InterviewDefaults.DIFFICULTY,
            null,
            null,
            paperId,
            "PAPER"
        );
        heartbeat(payload);

        persistenceService.saveExtractionSummaryIfOwned(
            paperId, payload.attemptId(), result, session.sessionId());
        PaperFlowLog.event("PAPER_EXTRACT_SESSION_CREATED sessionId={}, questions={}, "
                + "subjectId={}, totalScore={}, warnings={}",
            session.sessionId(), questions.size(), result.subjectId(), result.totalScore(),
            result.warnings());
        log.info("试卷切题完成并已生成练习: paperId={}, sessionId={}, questions={}",
            paperId, session.sessionId(), questions.size());
    }

    private PaperExtractionResult extractStructure(Long paperId, Runnable heartbeat) {
        long structureStart = System.nanoTime();
        PaperFlowLog.event("PAPER_EXTRACT_STRUCTURE_BEGIN paperId={}", paperId);
        PaperStructureService.BuiltStructure built;
        try {
            built = structureService.build(paperId);
        } catch (RuntimeException e) {
            throw stageFailure(paperId, "STRUCTURE", e);
        }
        PaperFlowLog.event("PAPER_EXTRACT_STRUCTURE_END paperId={}, questions={}, totalScore={}, "
                + "warnings={}, durationMs={}",
            paperId, built.questions().size(), built.totalScore(), built.warnings(),
            elapsedMs(structureStart));

        long answerKeyStart = System.nanoTime();
        PaperFlowLog.event("PAPER_EXTRACT_ANSWER_KEY_BEGIN paperId={}, questions={}",
            paperId, built.questions().size());
        AnswerKeyPreparationService.PreparedAnswerKeys prepared;
        try {
            prepared = answerKeyPreparationService.prepare(paperId, built, heartbeat);
        } catch (RuntimeException e) {
            throw stageFailure(paperId, "ANSWER_KEY", e);
        }
        PaperFlowLog.event("PAPER_EXTRACT_ANSWER_KEY_END paperId={}, questions={}, warnings={}, "
                + "durationMs={}",
            paperId, prepared.structure().questions().size(), prepared.warnings(),
            elapsedMs(answerKeyStart));
        PaperStructureService.BuiltStructure ready = prepared.structure();
        List<String> warnings = new java.util.ArrayList<>(ready.warnings());
        warnings.addAll(prepared.warnings());
        log.info("结构模型切题完成: paperId={}, questions={}, totalScore={}",
            paperId, ready.questions().size(), ready.totalScore());
        return new PaperExtractionResult(ready.subjectId(), ready.paperTree(),
            ready.questions(), ready.totalScore(), java.util.Map.of(), warnings);
    }

    private BusinessException stageFailure(Long paperId, String stage, RuntimeException error) {
    if (error instanceof BusinessException business
        && (Objects.equals(business.getCode(), ErrorCode.AI_RATE_LIMIT_EXCEEDED.getCode())
            || Objects.equals(business.getCode(), ErrorCode.AI_SERVICE_TIMEOUT.getCode()))) {
      PaperFlowLog.error("PAPER_EXTRACT_STAGE_FAILED stage={}, paperId={}, retryable=true",
          error, stage, paperId);
      return business;
    }
    if (RetryableFailureClassifier.isRateLimitFailure(error)) {
      PaperFlowLog.error("PAPER_EXTRACT_STAGE_FAILED stage={}, paperId={}, retryable=true",
          error, stage, paperId);
      return new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED,
          stage + ": AI 调用频率超限，请稍后重试", error);
    }
    if (error instanceof VisionRequestRejectedException
        || error.getCause() instanceof VisionRequestRejectedException) {
      PaperFlowLog.error("PAPER_EXTRACT_STAGE_FAILED stage={}, paperId={}, retryable=true",
          error, stage, paperId);
      return new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED,
          stage + ": 图片识别并发已满，请稍后重试", error);
    }
        String detail = error instanceof BusinessException && error.getMessage() != null
            ? error.getMessage() : error.getClass().getName();
        boolean timeout = isTimeout(error)
        || StructuredOutputInvoker.isTransportFailure(error)
            || error.getMessage() != null
            && (error.getMessage().contains("超时") || error.getMessage().contains("timeout"));
        if (timeout) {
            detail = "模型请求超时或连接中断";
        }
        PaperFlowLog.error("PAPER_EXTRACT_STAGE_FAILED stage={}, paperId={}, timeout={}",
            error, stage, paperId, timeout);
        ErrorCode code = timeout ? ErrorCode.AI_SERVICE_TIMEOUT : ErrorCode.PAPER_EXTRACTION_FAILED;
        return new BusinessException(code,
            stage + ": " + detail, error);
    }

    private boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String type = current.getClass().getSimpleName();
            if (type.contains("Timeout") || type.contains("OpenAIIoException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void heartbeat(PaperExtractPayload payload) {
        int updated = resumeRepository.heartbeatAnalyzeProcessing(
            payload.paperId(), payload.attemptId(), java.time.LocalDateTime.now());
        PaperFlowLog.event("PAPER_EXTRACT_HEARTBEAT paperId={}, attemptId={}, updated={}",
            payload.paperId(), payload.attemptId(), updated);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "试卷切题任务执行权已失效");
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    protected void markCompleted(PaperExtractPayload payload) {
        // 条件写入：状态已不是 PROCESSING 时 no-op，防止覆盖他人结果
        int updated = resumeRepository.completeAnalyzeIfProcessing(
            payload.paperId(), payload.attemptId(), java.time.LocalDateTime.now());
        PaperFlowLog.event("PAPER_EXTRACT_COMPLETE paperId={}, attemptId={}, updated={}",
            payload.paperId(), payload.attemptId(), updated);
    }

    @Override
    protected void markFailed(PaperExtractPayload payload, String error) {
        int updated = resumeRepository.failAnalyzeIfProcessing(
            payload.paperId(), payload.attemptId(), truncateError(error),
            java.time.LocalDateTime.now());
        PaperFlowLog.warn("PAPER_EXTRACT_FAILED paperId={}, attemptId={}, updated={}, error={}",
            payload.paperId(), payload.attemptId(), updated, error);
    }

    @Override
    protected void retryMessage(PaperExtractPayload payload, int retryCount) {
        Long paperId = payload.paperId();
        // 条件领取的配套重置：PROCESSING → PENDING 成功才重新投递，否则只记录并 ACK 丢弃
        int reset = resumeRepository.resetAnalyzeToPending(
            paperId, payload.attemptId(), java.time.LocalDateTime.now());
        if (reset != 1) {
            PaperFlowLog.warn("PAPER_EXTRACT_RETRY_RESET_IGNORED paperId={}, attemptId={}, "
                    + "retryCount={}, updated={}",
                paperId, payload.attemptId(), retryCount, reset);
            log.warn("重试重置失败（任务已完成或状态不再匹配），ACK 丢弃: paperId={}, retryCount={}",
                paperId, retryCount);
            return;
        }
        try {
            Map<String, String> message = new LinkedHashMap<>();
            message.put(AsyncTaskStreamConstants.FIELD_PAPER_ID, paperId.toString());
            message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
            message.putAll(aiContextMessageFields());

            redisService().streamAdd(
                AsyncTaskStreamConstants.PAPER_EXTRACT_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            PaperFlowLog.event("PAPER_EXTRACT_RETRY_ENQUEUED paperId={}, attemptId={}, "
                    + "retryCount={}, message={}",
                paperId, payload.attemptId(), retryCount, message);
            log.info("试卷切题任务已重新入队: paperId={}, retryCount={}", paperId, retryCount);

        } catch (Exception e) {
            PaperFlowLog.error("PAPER_EXTRACT_RETRY_ENQUEUE_FAILED paperId={}, attemptId={}, "
                    + "retryCount={}",
                e, paperId, payload.attemptId(), retryCount);
            // 重投失败标 FAILED，避免停在 PENDING 被恢复调度器反复补投
            resumeRepository.failAnalyzeIfPending(
                paperId, "重试入队失败", java.time.LocalDateTime.now());
        }
    }

    @Override
    protected void logTaskFailure(PaperExtractPayload payload, Exception error) {
        PaperFlowLog.error("PAPER_EXTRACT_TASK_FAILED paperId={}, attemptId={}",
            error, payload.paperId(), payload.attemptId());
    }
}
