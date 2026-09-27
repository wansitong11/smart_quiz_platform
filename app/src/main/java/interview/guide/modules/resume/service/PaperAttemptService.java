package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.resume.model.PracticePaperEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 试卷作答服务：把「已切题的试卷」派生成一次可作答的练习会话。
 * <p>
 * 切题只做一次（题目与层级树存在试卷关联的练习会话里），作答可以反复进行：
 * 上一次还没交卷就继续用它，已经交卷则基于同一棵树新开一次，历史记录保留。
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
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    /**
     * 开始（或继续）一次作答。
     *
     * @param paperId 试卷 / 练习材料 ID
     * @return 可直接进入作答页的练习会话
     */
    public InterviewSessionDTO startAttempt(Long paperId) {
        // 同一份试卷的「继续 or 新开」判定要串行，避免连点两次各建一次练习
        return redisService.executeWithLock(
            "paper:attempt:lock:" + paperId, 3, 10, TimeUnit.SECONDS,
            () -> startAttemptLocked(paperId));
    }

    private InterviewSessionDTO startAttemptLocked(Long paperId) {
        Optional<PracticePaperEntity> paperOpt = resumePersistenceService.findById(paperId);
        if (paperOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        List<PracticeSessionEntity> sessions = interviewPersistenceService.findByResumeId(paperId).stream()
            .filter(session -> SOURCE_TYPE_PAPER.equals(session.getSourceType()))
            .toList();
        if (sessions.isEmpty()) {
            throw new BusinessException(ErrorCode.PAPER_NO_QUESTION_FOUND,
                "该材料还没有切题结果，请先完成切题");
        }

        PracticeSessionEntity latest = sessions.get(0);
        if (isUnfinished(latest)) {
            log.info("继续未完成的作答: paperId={}, sessionId={}", paperId, latest.getSessionId());
            return interviewSessionService.getSession(latest.getSessionId());
        }

        PaperTree paperTree = PaperTree.parse(objectMapper, latest.getPaperJson());
        List<InterviewQuestionDTO> questions = paperTree.flattenQuestions().stream()
            .map(InterviewQuestionDTO::withoutAnswer)
            .toList();

        InterviewSessionDTO session = interviewSessionService.createSessionFromQuestions(
            questions,
            paperTree.withQuestions(questions),
            latest.getLlmProvider(),
            latest.getSkillId(),
            latest.getDifficulty(),
            null,
            latest.getSubjectCategory(),
            paperId,
            SOURCE_TYPE_PAPER
        );
        log.info("试卷重新作答: paperId={}, 新会话={}, 题数={}", paperId, session.sessionId(), questions.size());
        return session;
    }

    /**
     * 从一次已有的试卷练习再开一次作答（报告页「再练一遍」）。
     *
     * @param sessionId 已有的试卷练习会话
     * @return 可直接进入作答页的练习会话
     */
    public InterviewSessionDTO startAttemptFromSession(String sessionId) {
        PracticeSessionEntity session = interviewPersistenceService.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        Long paperId = session.getResumeId();
        if (paperId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "这次练习不是来自上传的试卷，无法再练一遍");
        }
        return startAttempt(paperId);
    }

    private boolean isUnfinished(PracticeSessionEntity session) {
        return session.getStatus() == PracticeSessionEntity.SessionStatus.CREATED
            || session.getStatus() == PracticeSessionEntity.SessionStatus.IN_PROGRESS;
    }
}
