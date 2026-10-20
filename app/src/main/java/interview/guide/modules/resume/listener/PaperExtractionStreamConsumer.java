package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.PageTextMerger;
import interview.guide.modules.resume.service.PaperExtractionResult;
import interview.guide.modules.resume.service.PaperQuestionExtractionService;
import interview.guide.modules.resume.service.PaperStructureService;
import interview.guide.modules.resume.service.ResumeParseService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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

    private final PaperQuestionExtractionService extractionService;
    private final InterviewSessionService interviewSessionService;
    private final ResumePersistenceService persistenceService;
    private final ResumeRepository resumeRepository;
    private final ResumeParseService parseService;
    private final PaperPageRepository pageRepository;
    private final PageTextMerger pageTextMerger;
    private final PaperStructureService structureService;

    public PaperExtractionStreamConsumer(
        RedisService redisService,
        PaperQuestionExtractionService extractionService,
        InterviewSessionService interviewSessionService,
        ResumePersistenceService persistenceService,
        ResumeRepository resumeRepository,
        ResumeParseService parseService,
        PaperPageRepository pageRepository,
        PageTextMerger pageTextMerger,
        PaperStructureService structureService
    ) {
        super(redisService);
        this.extractionService = extractionService;
        this.interviewSessionService = interviewSessionService;
        this.persistenceService = persistenceService;
        this.resumeRepository = resumeRepository;
        this.parseService = parseService;
        this.pageRepository = pageRepository;
        this.pageTextMerger = pageTextMerger;
        this.structureService = structureService;
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
    protected String threadName() {
        return "paper-extract-consumer";
    }

    @Override
    protected PaperExtractPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String paperIdStr = data.get(AsyncTaskStreamConstants.FIELD_PAPER_ID);
        if (paperIdStr == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        // 正文以数据库实体为事实来源，消息里只传 ID
        return new PaperExtractPayload(Long.parseLong(paperIdStr));
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
        return claimed;
    }

    @Override
    protected void processBusiness(PaperExtractPayload payload) {
        Long paperId = payload.paperId();
        PracticePaperEntity paper = resumeRepository.findById(paperId).orElse(null);
        if (paper == null) {
            log.warn("试卷已被删除，跳过切题任务: paperId={}", paperId);
            return;
        }

        String paperText = paper.getResumeText();
        if (isBlank(paperText)) {
            // 历史数据正文为空时，从对象存储恢复文本并以独立短事务回填后再切题
            paperText = parseService.downloadAndParseContent(
                paper.getStorageKey(), paper.getOriginalFilename());
            heartbeat(payload);
            if (isBlank(paperText)) {
                throw new BusinessException(ErrorCode.PAPER_PARSE_FAILED, "无法获取试卷文本内容");
            }
            persistenceService.updateResumeText(paperId, paperText);
        }

        PaperExtractionResult result = extractStructure(paperId, paperText);
        // LLM 切题完成心跳（分批调用已结束，落库前确认执行权仍在自己手里）
        heartbeat(payload);
        if (resumeRepository.findById(paperId).isEmpty()) {
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
        log.info("试卷切题完成并已生成练习: paperId={}, sessionId={}, questions={}",
            paperId, session.sessionId(), questions.size());
    }

    private PaperExtractionResult extractStructure(Long paperId, String paperText) {
        try {
            PaperStructureService.BuiltStructure built = structureService.build(paperId);
            log.info("结构模型切题完成: paperId={}, questions={}, totalScore={}",
                paperId, built.questions().size(), built.totalScore());
            return new PaperExtractionResult(built.subjectId(), built.paperTree(),
                built.questions(), built.totalScore(), java.util.Map.of(), built.warnings());
        } catch (Exception e) {
            boolean legacyPaper = resumeRepository.findById(paperId)
                .map(paper -> paper.getRecognitionStatus() == null
                    || paper.getRecognitionStatus() == RecognitionStatus.NONE)
                .orElse(true);
            if (!legacyPaper) {
                // 新链路（扫描件/图片/DOCX）不再回退到旧正则解析：
                // 旧解析不认材料层级，会把答案页、说明页也当成题目，产生大量脏题。
                log.error("结构化失败，且新链路不回退旧解析: paperId={}, error={}",
                    paperId, ErrorLogSanitizer.summarize(e));
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "试卷结构化失败");
            }
            log.warn("结构模型不可用，回退旧切题: paperId={}, error={}",
                paperId, ErrorLogSanitizer.summarize(e));
            PageTextMerger.PageLocator pageLocator = resolvePageLocator(paperId, paperText);
            return extractionService.extract(paperText, null, pageLocator);
        }
    }

    private void heartbeat(PaperExtractPayload payload) {
        int updated = resumeRepository.heartbeatAnalyzeProcessing(
            payload.paperId(), payload.attemptId(), java.time.LocalDateTime.now());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "试卷切题任务执行权已失效");
        }
    }

    /** 扫描件试卷：题干 → 页码定位器，用与确认时相同的合并算法重算，保证偏移一致。 */
    private PageTextMerger.PageLocator resolvePageLocator(Long paperId, String paperText) {
        List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
        if (pages.isEmpty()) {
            return null;
        }
        List<PaperPageEntity> readyPages = pages.stream()
            .filter(page -> page.getStatus() == PageStatus.READY)
            .toList();
        if (readyPages.isEmpty()) {
            return null;
        }
        PageTextMerger.MergeResult merged = pageTextMerger.merge(readyPages);
        return new PageTextMerger.PageLocator(paperText, merged.pageRanges());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    protected void markCompleted(PaperExtractPayload payload) {
        // 条件写入：状态已不是 PROCESSING 时 no-op，防止覆盖他人结果
        resumeRepository.completeAnalyzeIfProcessing(
            payload.paperId(), payload.attemptId(), java.time.LocalDateTime.now());
    }

    @Override
    protected void markFailed(PaperExtractPayload payload, String error) {
        resumeRepository.failAnalyzeIfProcessing(
            payload.paperId(), payload.attemptId(), truncateError(error),
            java.time.LocalDateTime.now());
    }

    @Override
    protected void retryMessage(PaperExtractPayload payload, int retryCount) {
        Long paperId = payload.paperId();
        // 条件领取的配套重置：PROCESSING → PENDING 成功才重新投递，否则只记录并 ACK 丢弃
        int reset = resumeRepository.resetAnalyzeToPending(
            paperId, payload.attemptId(), java.time.LocalDateTime.now());
        if (reset != 1) {
            log.warn("重试重置失败（任务已完成或状态不再匹配），ACK 丢弃: paperId={}, retryCount={}",
                paperId, retryCount);
            return;
        }
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_PAPER_ID, paperId.toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.PAPER_EXTRACT_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("试卷切题任务已重新入队: paperId={}, retryCount={}", paperId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: paperId={}, error={}", paperId,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            // 重投失败标 FAILED，避免停在 PENDING 被恢复调度器反复补投
            resumeRepository.failAnalyzeIfPending(
                paperId, "重试入队失败", java.time.LocalDateTime.now());
        }
    }
}
