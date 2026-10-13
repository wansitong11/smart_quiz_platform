package interview.guide.modules.interview.service;

import interview.guide.modules.auth.security.OwnerScope;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 面试持久化服务
 * 面试会话和答案的持久化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPersistenceService {

    private final OwnerScope ownerScope;
    
    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final ResumeRepository resumeRepository;
    private final ObjectMapper objectMapper;
    
    /**
     * 保存新的面试会话（支持可选简历）
     */
    @Transactional(rollbackFor = Exception.class)
    public PracticeSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              PaperTree paperTree,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty) {
        return saveSession(sessionId, resumeId, totalQuestions, paperTree, llmProvider, skillId, difficulty,
            "NORMAL", null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public PracticeSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              PaperTree paperTree,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty,
                                              String sourceType,
                                              Long knowledgeBaseId,
                                              String subjectCategory) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, paperTree, llmProvider,
            skillId, difficulty, sourceType, knowledgeBaseId, subjectCategory, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public PracticeSessionEntity saveIdempotentSession(String sessionId, Long resumeId,
                                                        int totalQuestions,
                                                        PaperTree paperTree,
                                                        String llmProvider,
                                                        String skillId,
                                                        String difficulty,
                                                        String requestId) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, paperTree, llmProvider,
            skillId, difficulty, "NORMAL", null, null, requestId);
    }

    private PracticeSessionEntity saveSessionInternal(String sessionId, Long resumeId,
                                                       int totalQuestions,
                                                       PaperTree paperTree,
                                                       String llmProvider,
                                                       String skillId,
                                                       String difficulty,
                                                       String sourceType,
                                                       Long knowledgeBaseId,
                                                       String subjectCategory,
                                                       String requestId) {
        try {
            PracticeSessionEntity session = new PracticeSessionEntity();
            session.setSessionId(sessionId);

            // 归属人：优先当前登录用户。异步切题线程没有登录上下文（上传 / 重切题都是后台切完再建这份会话），
            // 这时沿用试卷自身的归属人——否则会取不到用户，把已经切好的题判成切题失败。
            Optional<PracticePaperEntity> paperOpt = resumeId != null
                ? resumeRepository.findById(resumeId)
                : Optional.empty();
            Long ownerId = ownerScope.optionalOwnerId();
            if (ownerId == null) {
                ownerId = paperOpt.map(PracticePaperEntity::getOwnerId).orElse(null);
            }
            session.setOwnerId(ownerId);
            session.setRequestId(requestId);
            session.setTotalQuestions(totalQuestions);
            session.setCurrentQuestionIndex(0);
            session.setStatus(PracticeSessionEntity.SessionStatus.CREATED);
            session.setPaperJson(paperTree.toJson(objectMapper));
            session.setLlmProvider(llmProvider != null ? llmProvider : "default");
            session.setSkillId(skillId != null ? skillId : InterviewDefaults.SKILL_ID);
            session.setDifficulty(difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY);
            session.setSourceType(sourceType != null ? sourceType : "NORMAL");
            session.setKnowledgeBaseId(knowledgeBaseId);
            session.setSubjectCategory(subjectCategory);

            // 简历可选：有 resumeId 则关联简历
            paperOpt.ifPresent(session::setResume);

            PracticeSessionEntity saved = sessionRepository.save(session);
            log.info("面试会话已保存: sessionId={}, skillId={}, resumeId={}, sourceType={}",
                sessionId, skillId, resumeId, session.getSourceType());

            return saved;
        } catch (JacksonException e) {
            log.error("序列化问题列表失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存会话失败");
        }
    }
    
    /**
     * 更新会话状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSessionStatus(String sessionId, PracticeSessionEntity.SessionStatus status) {
        Optional<PracticeSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            PracticeSessionEntity session = sessionOpt.get();
            session.setStatus(status);
            if (status == PracticeSessionEntity.SessionStatus.COMPLETED ||
                status == PracticeSessionEntity.SessionStatus.EVALUATED) {
                session.setCompletedAt(LocalDateTime.now());
            }
            sessionRepository.save(session);
        }
    }

    /**
     * 更新评估状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        Optional<PracticeSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            PracticeSessionEntity session = sessionOpt.get();
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            } else {
                session.setEvaluateError(null);
            }
            sessionRepository.save(session);
            log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
        }
    }
    
    /**
     * 更新当前问题索引
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentQuestionIndex(String sessionId, int index) {
        Optional<PracticeSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            PracticeSessionEntity session = sessionOpt.get();
            session.setCurrentQuestionIndex(index);
            session.setStatus(PracticeSessionEntity.SessionStatus.IN_PROGRESS);
            sessionRepository.save(session);
        }
    }
    
    /**
     * 保存面试答案
     */
    /**
     * 保存模拟测验的组卷参数快照（用于「再测一次」）。
     * 与生成分离：组卷先落库拿到 sessionId，再把参数写回，避免组卷失败时留下脏配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateQuizConfig(String sessionId, String quizConfigJson) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setQuizConfigJson(quizConfigJson);
            sessionRepository.save(session);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public PracticeAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                            String question, String category,
                                            String userAnswer, int score, String feedback) {
        Optional<PracticeSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "答题记录不存在或无权访问");
        }

        PracticeAnswerEntity answer = answerRepository
            .findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex)
            .orElseGet(() -> {
                PracticeAnswerEntity created = new PracticeAnswerEntity();
                created.setSession(sessionOpt.get());
                created.setQuestionIndex(questionIndex);
                return created;
            });

        answer.setQuestion(question);
        answer.setCategory(category);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);

        PracticeAnswerEntity saved = answerRepository.save(answer);
        log.info("面试答案已保存: sessionId={}, questionIndex={}, score={}", 
                sessionId, questionIndex, score);
        
        return saved;
    }
    
    /**
     * 单题作答落库数据（整卷交卷使用）。
     *
     * @param questionIndex 题目索引
     * @param answerText    参与批改与展示的答案文本（选择题为选项 key 序列）
     * @param answerJson    结构化作答 JSON，可为空
     * @param autoScored    该题是否由系统按标准答案直接判分
     */
    public record AnswerSubmission(int questionIndex, String answerText, String answerJson, boolean autoScored) {}

    /**
     * 整卷交卷：批量 upsert 全部作答并一次性把会话置为 COMPLETED。
     * <p>
     * 与逐题提交的区别：只查一次已有作答、只 flush 一次、只更新一次会话，
     * 避免一次交卷打出 N 次数据库往返。
     * </p>
     *
     * @param submissions 本次提交的作答（未作答的题目不落库，批改时按空答案处理）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveWholePaperAnswers(String sessionId,
                                      List<InterviewQuestionDTO> questions,
                                      List<AnswerSubmission> submissions) {
        PracticeSessionEntity session = sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "答题记录不存在或无权访问"));

        java.util.Map<Integer, PracticeAnswerEntity> existing = answerRepository
            .findBySession_SessionIdOrderByQuestionIndex(sessionId).stream()
            .collect(java.util.stream.Collectors.toMap(
                PracticeAnswerEntity::getQuestionIndex, answer -> answer, (first, second) -> first));

        List<PracticeAnswerEntity> toSave = new java.util.ArrayList<>(submissions.size());
        for (AnswerSubmission submission : submissions) {
            Integer index = submission.questionIndex();
            if (index == null || index < 0 || index >= questions.size()) {
                continue;
            }
            InterviewQuestionDTO question = questions.get(index);
            PracticeAnswerEntity answer = existing.get(index);
            if (answer == null) {
                answer = new PracticeAnswerEntity();
                answer.setSession(session);
                answer.setQuestionIndex(index);
            }
            answer.setQuestion(question.question());
            answer.setCategory(question.category());
            answer.setUserAnswer(submission.answerText() != null ? submission.answerText() : "");
            answer.setAnswerJson(submission.answerJson());
            answer.setAutoScored(submission.autoScored());
            // 分数在批改阶段写回
            answer.setScore(0);
            answer.setFeedback(null);
            toSave.add(answer);
        }
        answerRepository.saveAll(toSave);

        session.setCurrentQuestionIndex(questions.size());
        session.setStatus(PracticeSessionEntity.SessionStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        sessionRepository.save(session);

        log.info("整卷交卷已保存: sessionId={}, 提交题数={}, 总题数={}",
            sessionId, toSave.size(), questions.size());
    }

    /**
     * 保存面试报告
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveReport(String sessionId, InterviewReportDTO report) {
        try {
            Optional<PracticeSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
            if (sessionOpt.isEmpty()) {
                log.warn("会话不存在: {}", sessionId);
                return;
            }

            PracticeSessionEntity session = sessionOpt.get();
            session.setOverallScore(report.overallScore());
            session.setOverallFeedback(report.overallFeedback());
            session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
            session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
            session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
            session.setStatus(PracticeSessionEntity.SessionStatus.EVALUATED);
            session.setCompletedAt(LocalDateTime.now());

            sessionRepository.save(session);

            // 查询已存在的答案，建立索引
            List<PracticeAnswerEntity> existingAnswers = answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
            java.util.Map<Integer, PracticeAnswerEntity> answerMap = existingAnswers.stream()
                .collect(java.util.stream.Collectors.toMap(
                    PracticeAnswerEntity::getQuestionIndex,
                    a -> a,
                    (a1, a2) -> a1
                ));

            // 建立参考答案索引
            java.util.Map<Integer, InterviewReportDTO.ReferenceAnswer> refAnswerMap = report.referenceAnswers().stream()
                .collect(java.util.stream.Collectors.toMap(
                    InterviewReportDTO.ReferenceAnswer::questionIndex,
                    r -> r,
                    (r1, r2) -> r1
                ));

            List<PracticeAnswerEntity> answersToSave = new java.util.ArrayList<>();

            // 遍历所有评估结果，更新或创建答案记录
            for (InterviewReportDTO.QuestionEvaluation eval : report.questionDetails()) {
                PracticeAnswerEntity answer = answerMap.get(eval.questionIndex());

                if (answer == null) {
                    // 未回答的题目，创建新记录
                    answer = new PracticeAnswerEntity();
                    answer.setSession(session);
                    answer.setQuestionIndex(eval.questionIndex());
                    answer.setQuestion(eval.question());
                    answer.setCategory(eval.category());
                    answer.setUserAnswer(null);  // 未回答
                    log.debug("为未回答的题目 {} 创建答案记录", eval.questionIndex());
                }

                // 更新评分和反馈
                answer.setScore(eval.score());
                answer.setFeedback(eval.feedback());

                // 设置参考答案和关键点
                InterviewReportDTO.ReferenceAnswer refAns = refAnswerMap.get(eval.questionIndex());
                if (refAns != null) {
                    answer.setReferenceAnswer(refAns.referenceAnswer());
                    if (refAns.keyPoints() != null && !refAns.keyPoints().isEmpty()) {
                        answer.setKeyPointsJson(objectMapper.writeValueAsString(refAns.keyPoints()));
                    }
                }

                answersToSave.add(answer);
            }

            answerRepository.saveAll(answersToSave);
            log.info("面试报告已保存: sessionId={}, score={}, 答案数={}",
                sessionId, report.overallScore(), answersToSave.size());

        } catch (JacksonException e) {
            log.error("序列化报告失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 根据会话ID获取会话
     */
    public Optional<PracticeSessionEntity> findBySessionId(String sessionId) {
        // 异步消费者/调度器没有请求上下文，effectiveOwnerId() 返回 null → 不过滤，行为与改造前一致
        return sessionRepository.findBySessionIdScoped(sessionId, ownerScope.effectiveOwnerId());
    }

    /**
     * 读取会话的试卷层级树（批改上下文与层级渲染使用）。
     */
    public java.util.Optional<PaperTree> findPaperTree(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
            .map(session -> PaperTree.parse(objectMapper, session.getPaperJson()));
    }

    public Optional<PracticeSessionEntity> findByRequestId(String requestId) {
        return sessionRepository.findByRequestId(requestId);
    }
    
    /**
     * 获取简历的所有面试记录
     */
    public List<PracticeSessionEntity> findByResumeId(Long resumeId) {
        return sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    /**
     * 获取所有面试记录（按创建时间倒序）
     */
    public List<PracticeSessionEntity> findAll() {
        return sessionRepository.findAllScoped(ownerScope.effectiveOwnerId());
    }
    
    /**
     * 删除简历的所有面试会话
     * 由于PracticeSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionsByResumeId(Long resumeId) {
        List<PracticeSessionEntity> sessions = sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
        if (!sessions.isEmpty()) {
            sessionRepository.deleteAll(sessions);
            log.info("已删除 {} 个面试会话（包含所有答案）", sessions.size());
        }
    }
    
    /**
     * 删除单个面试会话
     * 由于PracticeSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionBySessionId(String sessionId) {
        // 归属校验：别人的会话一律按"不存在"处理（决策 5：越权返回 404，不暴露存在性）
        PracticeSessionEntity session = findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "答题记录不存在或无权访问"));
        sessionRepository.delete(session);
        log.info("已删除面试会话: sessionId={}", sessionId);
    }
    
    /**
     * 查找未完成的面试会话（CREATED或IN_PROGRESS状态）
     */
    public Optional<PracticeSessionEntity> findUnfinishedSession(Long resumeId) {
        List<PracticeSessionEntity.SessionStatus> unfinishedStatuses = List.of(
            PracticeSessionEntity.SessionStatus.CREATED,
            PracticeSessionEntity.SessionStatus.IN_PROGRESS
        );
        return sessionRepository.findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(resumeId, unfinishedStatuses);
    }
    
    /**
     * 根据会话ID查找所有答案
     */
    public List<PracticeAnswerEntity> findAnswersBySessionId(String sessionId) {
        return answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
    }

    private static final int MAX_HISTORICAL_QUESTIONS = 60;

    /**
     * 获取历史提问列表（结构化，按分类压缩用）。
     * 有 resumeId 时精确匹配 resumeId + skillId；无 resumeId 时按 skillId 查全部（通用模式兜底）。
     */
    public List<HistoricalQuestion> getHistoricalQuestions(String skillId, Long resumeId) {
        List<PracticeSessionEntity> sessions;
        if (resumeId != null) {
            sessions = sessionRepository.findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(resumeId, skillId);
        } else {
            sessions = sessionRepository.findTop10BySkillIdOrderByCreatedAtDesc(skillId);
        }

        log.info("加载历史题目: skillId={}, resumeId={}, 查到 {} 个历史会话", skillId, resumeId, sessions.size());

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<HistoricalQuestion> result = sessions.stream()
            .map(PracticeSessionEntity::getPaperJson)
            .filter(json -> json != null && !json.isEmpty())
            .flatMap(json -> {
                try {
                    List<InterviewQuestionDTO> questions = PaperTree.parse(objectMapper, json).flattenQuestions();
                    return questions.stream()
                        .filter(q -> !q.isFollowUp())
                        .map(q -> new HistoricalQuestion(q.question(), q.type(), q.topicSummary()));
                } catch (Exception e) {
                    log.error("解析历史问题JSON失败", e);
                    return java.util.stream.Stream.<HistoricalQuestion>empty();
                }
            })
            .filter(hq -> seen.add(hq.question()))
            .limit(MAX_HISTORICAL_QUESTIONS)
            .toList();

        log.info("历史题目加载完成: 去重后 {} 道主问题，按分类: {}", result.size(),
            result.stream().collect(java.util.stream.Collectors.groupingBy(
                hq -> hq.type() != null ? hq.type() : "GENERAL",
                java.util.stream.Collectors.counting())));

        return result;
    }
}
