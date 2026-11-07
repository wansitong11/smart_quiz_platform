package interview.guide.modules.resume.service;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiCostControl;
import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.RecognitionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 试卷作答服务：把「已切题的试卷」派生成一次可作答的练习会话。
 * <p>
 * 作答可以反复进行：上一次还没交卷就继续用它；已经交卷则从当前试卷结构
 * 新开一次，历史记录保留。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperAttemptService {

    /** 试卷来源的练习会话 */
    private static final String SOURCE_TYPE_PAPER = "PAPER";

    private final ResumePersistenceService resumePersistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final InterviewSessionService interviewSessionService;
    private final PaperStructureService structureService;
    private final ResumeUploadService resumeUploadService;
    private final RedisService redisService;
    private final PaperContentGuard paperContentGuard;
    private final AiCostControl aiCostControl;

    /**
     * 开始（或继续）一次作答。
     *
     * @param paperId 试卷 / 练习材料 ID
     * @return 可直接进入作答页的练习会话
     */
    public InterviewSessionDTO startAttempt(Long paperId) {
        // 同一份试卷的「继续 or 新开」判定要串行，避免连点两次各建一次练习
        return redisService.executeWithLock(
            "paper:attempt:lock:" + paperId, 3, TimeUnit.SECONDS,
            () -> startAttemptSerialized(paperId));
    }

    private InterviewSessionDTO startAttemptSerialized(Long paperId) {
        AttemptPlan plan = paperContentGuard.withPaperLock(
            paperId, () -> inspectAttempt(paperId));
        if (plan.resumableSession() != null) {
            return plan.resumableSession();
        }

        // 结构构建可能调用模型或 S3，不占内容锁；构建后用内容代次做栅栏校验。
        PaperStructureService.BuiltStructure built = structureService.build(paperId);
        assertAnswerKeysUsable(paperId, built);

        return paperContentGuard.withPaperLock(
            paperId,
            () -> createAttemptIfContentUnchanged(paperId, plan.contentRevision(), built)
        );
    }

    private AttemptPlan inspectAttempt(Long paperId) {
        PracticePaperEntity paper = requirePaper(paperId);
        assertPaperReadyForAttempt(paper);

        List<PracticeSessionEntity> sessions = paperSessions(paperId);
        PracticeSessionEntity resumable = findResumableSession(sessions, paper);
        if (resumable != null) {
            interviewSessionService.markPaperAttemptStarted(resumable.getSessionId());
            log.info("继续未完成的作答: paperId={}, sessionId={}, status={}",
                paperId, resumable.getSessionId(), resumable.getStatus());
            return new AttemptPlan(
                interviewSessionService.getSession(resumable.getSessionId()),
                paper.getContentRevision()
            );
        }
        return new AttemptPlan(null, paper.getContentRevision());
    }

    private void assertAnswerKeysUsable(
        Long paperId,
        PaperStructureService.BuiltStructure built
    ) {
        PaperTree paperTree = built.paperTree();
        if (hasUnusableDeterministicAnswerKeys(paperTree)) {
            PracticePaperEntity paper = requirePaper(paperId);
            if (paper.getAnalysisStatus() == AsyncTaskStatus.PROCESSING) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "答案键正在重新生成，请稍后再开始作答");
            }
            reanalyzeWithIndependentBudget(paperId);
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "检测到部分题目缺少候选答案，已自动重新分析，请稍后再开始作答");
        }
    }

    /**
     * 开始作答触发的自动重切是独立异步任务：同步请求结束后会结算自己的预占，
     * 后台消费者必须使用新的操作号，否则重切成本会落在已结算的旧操作上而丢失。
     */
    private void reanalyzeWithIndependentBudget(Long paperId) {
        AiCallContext parent = AiCallContextHolder.get();
        String operationId = UUID.randomUUID().toString();
        AiCallContext asyncContext = new AiCallContext(
            parent == null ? null : parent.userId(),
            AiFeature.PAPER_EXTRACTION,
            "startAttempt.reanalyze",
            UUID.randomUUID().toString(),
            operationId,
            "ASYNC"
        );
        aiCostControl.reserveOrThrow(
            operationId,
            asyncContext.userId(),
            asyncContext.feature(),
            new AiCostEstimateInput(1L, null, null, null, null));
        try {
            AiCallContextHolder.runWith(
                asyncContext, () -> resumeUploadService.reanalyze(paperId));
        } catch (RuntimeException e) {
            aiCostControl.finalize(operationId);
            throw e;
        }
    }

    private InterviewSessionDTO createAttemptIfContentUnchanged(
        Long paperId,
        long expectedRevision,
        PaperStructureService.BuiltStructure built
    ) {
        PracticePaperEntity paper = requirePaper(paperId);
        if (paper.getContentRevision() != expectedRevision) {
            throw new BusinessException(
                ErrorCode.PAPER_CONTENT_CHANGED,
                "试卷内容在开始作答前已更新，请重新开始一次作答"
            );
        }
        assertPaperReadyForAttempt(paper);

        List<PracticeSessionEntity> sessions = paperSessions(paperId);
        PracticeSessionEntity resumable = findResumableSession(sessions, paper);
        if (resumable != null) {
            interviewSessionService.markPaperAttemptStarted(resumable.getSessionId());
            return interviewSessionService.getSession(resumable.getSessionId());
        }

        // 新一次作答必须从当前试卷结构重建，不能沿用上一场的 PaperTree。
        // 否则内容更新后会出现“旧题目树 + 新内容版本号”，绕过后续版本校验。
        PaperTree paperTree = built.paperTree();
        List<InterviewQuestionDTO> questions = paperTree.flattenQuestions().stream()
            .map(InterviewQuestionDTO::withoutAnswer)
            .toList();

        PracticeSessionEntity template = sessions.get(0);
        InterviewSessionDTO session = interviewSessionService.createSessionFromQuestions(
            questions,
            paperTree.withQuestions(questions),
            template.getLlmProvider(),
            template.getSkillId(),
            template.getDifficulty(),
            null,
            template.getSubjectCategory(),
            paperId,
            SOURCE_TYPE_PAPER
        );
        interviewSessionService.markPaperAttemptStarted(session.sessionId());
        log.info("试卷重新作答: paperId={}, 新会话={}, 题数={}", paperId, session.sessionId(), questions.size());
        return interviewSessionService.getSession(session.sessionId());
    }

    /**
     * 从一次已有的试卷练习再开一次作答（报告页「再练一遍」）。
     *
     * @param sessionId 已有的试卷练习会话
     * @return 可直接进入作答页的练习会话
     */
    public InterviewSessionDTO startAttemptFromSession(String sessionId) {
        PracticeSessionEntity session = interviewPersistenceService.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "答题记录不存在或无权访问"));
        Long paperId = session.getResumeId();
        if (paperId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "这次练习不是来自上传的试卷，无法再练一遍");
        }
        return startAttempt(paperId);
    }

    private void assertPaperReadyForAttempt(PracticePaperEntity paper) {
        RecognitionStatus recognition = paper.getRecognitionStatus();
        if (recognition != RecognitionStatus.NONE && recognition != RecognitionStatus.READY) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "试卷仍在识别或等待校对，请完成识别并确认切题后再开始作答"
            );
        }
        if (paper.getAnalysisStatus() == AsyncTaskStatus.PENDING
            || paper.getAnalysisStatus() == AsyncTaskStatus.PROCESSING) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "试卷正在切题，请稍后再开始作答"
            );
        }
    }

    private PracticePaperEntity requirePaper(Long paperId) {
        return resumePersistenceService.findById(paperId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private List<PracticeSessionEntity> paperSessions(Long paperId) {
        List<PracticeSessionEntity> sessions = interviewPersistenceService.findByResumeId(paperId).stream()
            .filter(session -> SOURCE_TYPE_PAPER.equals(session.getSourceType()))
            .toList();
        if (sessions.isEmpty()) {
            throw new BusinessException(ErrorCode.PAPER_NO_QUESTION_FOUND,
                "该材料还没有切题结果，请先完成切题");
        }
        return sessions;
    }

    private void assertContentRevision(PracticeSessionEntity session, PracticePaperEntity paper) {
        Long frozenRevision = session.getPaperContentRevision();
        if (!Objects.equals(frozenRevision, paper.getContentRevision())) {
            throw new BusinessException(
                ErrorCode.PAPER_CONTENT_CHANGED,
                "试卷内容已更新，请重新开始一次作答"
            );
        }
    }

    /**
     * 找到可以继续作答的会话。
     * <p>
     * 进行中会话如果版本已过期必须拒绝，避免覆盖用户答案；未开始草稿没有答案，
     * 版本过期时可以直接忽略，让后续流程按当前结构新建会话。这样旧草稿不会永久
     * 阻塞用户开始作答。
     */
    private PracticeSessionEntity findResumableSession(
        List<PracticeSessionEntity> sessions,
        PracticePaperEntity paper
    ) {
        for (PracticeSessionEntity session : sessions) {
            if (session.getStatus() == PracticeSessionEntity.SessionStatus.IN_PROGRESS) {
                assertContentRevision(session, paper);
                return session;
            }
            if (session.getStatus() == PracticeSessionEntity.SessionStatus.CREATED) {
                if (hasCurrentContentRevision(session, paper)) {
                    return session;
                }
                log.info("忽略内容版本过期的未开始草稿: paperId={}, sessionId={}, "
                        + "sessionRevision={}, paperRevision={}",
                    paper.getId(), session.getSessionId(), session.getPaperContentRevision(),
                    paper.getContentRevision());
            }
        }
        return null;
    }

    private boolean hasCurrentContentRevision(PracticeSessionEntity session,
                                              PracticePaperEntity paper) {
        return Objects.equals(session.getPaperContentRevision(), paper.getContentRevision());
    }

    private boolean hasUnusableDeterministicAnswerKeys(PaperTree tree) {
        return tree.flattenQuestions().stream()
        .filter(InterviewQuestionDTO::isDeterministicGrading)
        .anyMatch(question -> !question.hasUsableAnswerKey());
    }

    private record AttemptPlan(InterviewSessionDTO resumableSession, long contentRevision) {
    }
}
