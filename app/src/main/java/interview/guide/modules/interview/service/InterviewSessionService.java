package interview.guide.modules.interview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.BatchSubmitAnswerRequest;
import interview.guide.modules.interview.model.BatchSubmitAnswerResponse;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    private static final String CREATE_LOCK_PREFIX = "interview:create:";
    private static final String CREATE_RESULT_PREFIX = "interview:create:result:";
    private static final Duration CREATE_RESULT_TTL = Duration.ofDays(1);

    private final InterviewQuestionService questionService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;
    private final RedisService redisService;

    /**
     * 创建新的面试会话
     * 注意：如果已有未完成的会话，不会创建新的，而是返回现有会话
     * 前端应该先调用 findUnfinishedSession 检查，或者使用 forceCreate 参数强制创建
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        String requestId = normalizeRequestId(request.requestId());
        if (requestId == null) {
            return createSessionInternal(request);
        }

        return redisService.executeWithLock(
            CREATE_LOCK_PREFIX + requestId,
            185,
            600,
            TimeUnit.SECONDS,
            () -> createIdempotentSession(request, requestId)
        );
    }

    private InterviewSessionDTO createIdempotentSession(CreateInterviewRequest request, String requestId) {
        String resultKey = CREATE_RESULT_PREFIX + requestId;
        String cachedSessionId = redisService.get(resultKey);
        if (cachedSessionId != null) {
            log.info("复用缓存中的幂等创建请求: requestId={}, sessionId={}", requestId, cachedSessionId);
            return getSession(cachedSessionId);
        }

        Optional<PracticeSessionEntity> existing = persistenceService.findByRequestId(requestId);
        if (existing.isPresent()) {
            String existingSessionId = existing.get().getSessionId();
            log.info("从数据库恢复幂等创建请求: requestId={}, sessionId={}",
                requestId, existingSessionId);
            redisService.set(resultKey, existingSessionId, CREATE_RESULT_TTL);
            return getSession(existingSessionId);
        }

        InterviewSessionDTO created = createSessionInternal(request, requestId);
        redisService.set(resultKey, created.sessionId(), CREATE_RESULT_TTL);
        return created;
    }

    private InterviewSessionDTO createSessionInternal(CreateInterviewRequest request) {
        return createSessionInternal(request, null);
    }

    private InterviewSessionDTO createSessionInternal(CreateInterviewRequest request, String requestId) {
        // 如果指定了resumeId且未强制创建，检查是否有未完成的会话
        if (request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinishedOpt = findUnfinishedSession(request.resumeId());
            if (unfinishedOpt.isPresent()) {
                log.info("检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                    request.resumeId(), unfinishedOpt.get().sessionId());
                return unfinishedOpt.get();
            }
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String skillId = request.skillId() != null ? request.skillId() : InterviewDefaults.SKILL_ID;
        String difficulty = request.difficulty() != null ? request.difficulty() : InterviewDefaults.DIFFICULTY;

        log.info("创建新面试会话: {}, skill: {}, difficulty: {}, questionCount: {}, resumeId: {}",
            sessionId, skillId, difficulty, request.questionCount(), request.resumeId());

        // 获取历史问题（通用模式按 skillId 查询，有简历时按 resumeId + skillId 精确匹配）
        List<HistoricalQuestion> historicalQuestions =
            persistenceService.getHistoricalQuestions(skillId, request.resumeId());

        // 基于 Skill 生成面试问题
        List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
            request.llmProvider(),
            skillId,
            difficulty,
            request.resumeText(),
            request.questionCount(),
            historicalQuestions,
            request.customCategories(),
            request.jdText()
        );
        PaperTree paperTree = buildFlatTree(questions, skillId);

        if (requestId != null) {
            try {
                persistenceService.saveIdempotentSession(
                    sessionId,
                    request.resumeId(),
                    questions.size(),
                    paperTree,
                    request.llmProvider(),
                    skillId,
                    difficulty,
                    requestId
                );
            } catch (Exception e) {
                Optional<PracticeSessionEntity> concurrentlyCreated =
                    persistenceService.findByRequestId(requestId);
                if (concurrentlyCreated.isPresent()) {
                    return getSession(concurrentlyCreated.get().getSessionId());
                }
                log.error("持久化幂等面试会话失败: requestId={}", requestId, e);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "创建面试会话失败，请重试");
            }
        } else {
            try {
                persistenceService.saveSession(sessionId, request.resumeId(),
                    questions.size(), paperTree, request.llmProvider(), skillId, difficulty);
            } catch (Exception e) {
                log.warn("保存面试会话到数据库失败: {}", e.getMessage());
            }
        }

        // 幂等请求必须先成功落库，再写入易失缓存，保证进程异常后可从数据库恢复。
        sessionCache.saveSession(
            sessionId,
            request.resumeText() != null ? request.resumeText() : "",
            request.resumeId(),
            null,
            null,
            paperTree,
            0,
            SessionStatus.CREATED
        );

        return new InterviewSessionDTO(
            sessionId,
            request.resumeText() != null ? request.resumeText() : "",
            questions.size(),
            0,
            questions,
            SessionStatus.CREATED,
            null,
            null,
            paperTree,
            request.resumeId()
        );
    }

    public InterviewSessionDTO createSessionFromQuestions(List<InterviewQuestionDTO> questions,
                                                          String llmProvider,
                                                          String skillId,
                                                          String difficulty,
                                                          Long knowledgeBaseId,
                                                          String subjectCategory) {
        return createSessionFromQuestions(questions, llmProvider, skillId, difficulty,
            knowledgeBaseId, subjectCategory, null, "KNOWLEDGE_BASE");
    }

    /**
     * 用给定的题目列表创建练习会话（试卷切题、资料出题等场景共用）。
     *
     * @param paperId    试卷 / 材料 ID，可为空
     * @param sourceType 会话来源：NORMAL / KNOWLEDGE_BASE / PAPER
     */
    public InterviewSessionDTO createSessionFromQuestions(List<InterviewQuestionDTO> questions,
                                                          String llmProvider,
                                                          String skillId,
                                                          String difficulty,
                                                          Long knowledgeBaseId,
                                                          String subjectCategory,
                                                          Long paperId,
                                                          String sourceType) {
        return createSessionFromQuestions(questions, null, llmProvider, skillId, difficulty,
            knowledgeBaseId, subjectCategory, paperId, sourceType);
    }

    /**
     * 用给定的题目列表与试卷结构创建练习会话。
     *
     * @param paperTree 试卷层级树；为空时按「卷 → 全部题目」构造最小树
     */
    public InterviewSessionDTO createSessionFromQuestions(List<InterviewQuestionDTO> questions,
                                                          PaperTree paperTree,
                                                          String llmProvider,
                                                          String skillId,
                                                          String difficulty,
                                                          Long knowledgeBaseId,
                                                          String subjectCategory,
                                                          Long paperId,
                                                          String sourceType) {
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "面试题目不能为空");
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        PaperTree tree = paperTree != null ? paperTree : buildFlatTree(questions, subjectCategory);
        persistenceService.saveSession(
            sessionId, paperId, questions.size(), tree, llmProvider, skillId, difficulty,
            sourceType, knowledgeBaseId, subjectCategory);
        sessionCache.saveSession(sessionId, "", paperId, knowledgeBaseId, subjectCategory,
            tree, 0, SessionStatus.CREATED);

        return new InterviewSessionDTO(
            sessionId,
            "",
            questions.size(),
            0,
            questions,
            SessionStatus.CREATED,
            knowledgeBaseId,
            subjectCategory,
            tree,
            paperId
        );
    }

    /**
     * 没有层级信息时构造「卷 → 全部题目」的最小树。
     */
    private PaperTree buildFlatTree(List<InterviewQuestionDTO> questions, String title) {
        return PaperTree.fromFlatQuestions(questions, title);
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }

        String normalized = requestId.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestId 格式不正确");
        }
        return normalized;
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     */
    public InterviewSessionDTO getSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            return toDTO(cachedOpt.get());
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        return toDTO(restoredSession);
    }

    /**
     * 查找并恢复未完成的面试会话
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        try {
            // 1. 先从 Redis 缓存查找
            Optional<String> cachedSessionIdOpt = sessionCache.findUnfinishedSessionId(resumeId);
            if (cachedSessionIdOpt.isPresent()) {
                String sessionId = cachedSessionIdOpt.get();
                Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
                if (cachedOpt.isPresent()) {
                    log.debug("从 Redis 缓存找到未完成会话: resumeId={}, sessionId={}", resumeId, sessionId);
                    return Optional.of(toDTO(cachedOpt.get()));
                }
            }

            // 2. 缓存未命中，从数据库查找
            Optional<PracticeSessionEntity> entityOpt = persistenceService.findUnfinishedSession(resumeId);
            if (entityOpt.isEmpty()) {
                return Optional.empty();
            }

            PracticeSessionEntity entity = entityOpt.get();
            CachedSession restoredSession = restoreSessionFromEntity(entity);
            if (restoredSession != null) {
                return Optional.of(toDTO(restoredSession));
            }
        } catch (Exception e) {
            log.error("恢复未完成会话失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 查找并恢复未完成的面试会话，如果不存在则抛出异常
     */
    public InterviewSessionDTO findUnfinishedSessionOrThrow(Long resumeId) {
        return findUnfinishedSession(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到未完成的面试会话"));
    }

    /**
     * 从数据库恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromDatabase(String sessionId) {
        try {
            Optional<PracticeSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
            return entityOpt.map(this::restoreSessionFromEntity).orElse(null);
        } catch (Exception e) {
            log.error("从数据库恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从实体恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromEntity(PracticeSessionEntity entity) {
        try {
            // 解析试卷层级树并派生扁平题目列表
            PaperTree paperTree = PaperTree.parse(objectMapper, entity.getPaperJson());
            List<InterviewQuestionDTO> questions = new ArrayList<>(paperTree.flattenQuestions());

            // 恢复已保存的答案
            List<PracticeAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());
            for (PracticeAnswerEntity answer : answers) {
                int index = answer.getQuestionIndex();
                if (index >= 0 && index < questions.size()) {
                    InterviewQuestionDTO question = questions.get(index);
                    questions.set(index, question.withAnswer(answer.getUserAnswer()));
                }
            }

            SessionStatus status = convertStatus(entity.getStatus());

            // 已保存的答案写回题目载荷，保持缓存中的树与数据库一致
            sessionCache.saveSession(
                entity.getSessionId(),
                entity.getResume() != null ? entity.getResume().getResumeText() : "",
                entity.getResume() != null ? entity.getResume().getId() : null,
                entity.getKnowledgeBaseId(),
                entity.getSubjectCategory(),
                paperTree.withQuestions(questions),
                entity.getCurrentQuestionIndex(),
                status
            );

            log.info("从数据库恢复会话到 Redis: sessionId={}, currentIndex={}, status={}",
                entity.getSessionId(), entity.getCurrentQuestionIndex(), entity.getStatus());

            // 返回缓存的会话
            return sessionCache.getSession(entity.getSessionId()).orElse(null);
        } catch (Exception e) {
            log.error("恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private SessionStatus convertStatus(PracticeSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> SessionStatus.CREATED;
            case IN_PROGRESS -> SessionStatus.IN_PROGRESS;
            case COMPLETED -> SessionStatus.COMPLETED;
            case EVALUATED -> SessionStatus.EVALUATED;
        };
    }

    /**
     * 获取当前问题的响应（包含完成状态）
     */
    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of(
                "completed", true,
                "message", "所有问题已回答完毕"
            );
        }
        return Map.of(
            "completed", false,
            "question", question
        );
    }

    /**
     * 获取当前问题
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) {
            return null; // 所有问题已回答完
        }

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            session.setStatus(SessionStatus.IN_PROGRESS);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.IN_PROGRESS);

            // 同步到数据库
            try {
                persistenceService.updateSessionStatus(sessionId,
                    PracticeSessionEntity.SessionStatus.IN_PROGRESS);
            } catch (Exception e) {
                log.warn("更新会话状态失败: {}", e.getMessage());
            }
        }

        return questions.get(session.getCurrentIndex());
    }

    /**
     * 提交答案（并进入下一题）
     * 如果是最后一题，自动触发异步评估
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 移动到下一题
        int newIndex = index + 1;

        // 检查是否全部完成
        boolean hasNextQuestion = newIndex < questions.size();
        InterviewQuestionDTO nextQuestion = hasNextQuestion ? questions.get(newIndex) : null;

        SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;

        persistSubmittedAnswer(request, index, question, newIndex, newStatus);

        // 更新 Redis 缓存。DB 已经持久化成功，缓存失败时可由后续读取从数据库恢复。
        sessionCache.updateQuestions(request.sessionId(), questions);
        sessionCache.updateCurrentIndex(request.sessionId(), newIndex);
        if (newStatus == SessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.COMPLETED);
            enqueueEvaluationTask(request.sessionId());
        }

        log.info("会话 {} 提交答案: 问题{}, 剩余{}题",
            request.sessionId(), index, questions.size() - newIndex);

        return new SubmitAnswerResponse(
            hasNextQuestion,
            nextQuestion,
            newIndex,
            questions.size()
        );
    }

    private void persistSubmittedAnswer(SubmitAnswerRequest request, int index,
                                        InterviewQuestionDTO question, int newIndex,
                                        SessionStatus newStatus) {
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null  // 分数在报告生成时更新
            );
            persistenceService.updateCurrentQuestionIndex(request.sessionId(), newIndex);
            persistenceService.updateSessionStatus(request.sessionId(),
                newStatus == SessionStatus.COMPLETED
                    ? PracticeSessionEntity.SessionStatus.COMPLETED
                    : PracticeSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("保存答案到数据库失败: sessionId={}, questionIndex={}",
                request.sessionId(), index, e);
            throw new BusinessException(ErrorCode.INTERVIEW_ANSWER_SAVE_FAILED,
                "保存答案失败，请稍后重试");
        }
    }

    private void enqueueEvaluationTask(String sessionId) {
        persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        evaluateStreamProducer.sendEvaluateTask(sessionId);
        log.info("会话 {} 已完成所有问题，评估任务已入队", sessionId);
    }

    /**
     * 整卷交卷：一次提交全部答案，批量落库后直接进入批改。
     * <p>
     * 与逐题提交的差异：允许跳题与回改（答案按索引整体覆盖），
     * 交卷即结束会话，不做"提交后前进一题"的推进。
     * </p>
     */
    public BatchSubmitAnswerResponse submitWholePaper(String sessionId,
                                                      List<BatchSubmitAnswerRequest.AnswerItem> answers) {
        CachedSession session = getOrRestoreSession(sessionId);
        if (session.getStatus() == SessionStatus.COMPLETED
            || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        if (questions.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "会话没有题目");
        }
        if (answers == null || answers.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "答案列表不能为空");
        }

        // 索引 → 作答；重复索引视为请求错误，避免静默覆盖学生的作答
        Map<Integer, BatchSubmitAnswerRequest.AnswerItem> submitted = new LinkedHashMap<>();
        for (BatchSubmitAnswerRequest.AnswerItem item : answers) {
            int index = item.questionIndex();
            if (index < 0 || index >= questions.size()) {
                throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND,
                    "无效的问题索引: " + index);
            }
            if (submitted.putIfAbsent(index, item) != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "重复的问题索引: " + index);
            }
        }

        List<InterviewPersistenceService.AnswerSubmission> submissions = new ArrayList<>();
        submitted.forEach((index, item) -> {
            InterviewQuestionDTO question = questions.get(index);
            String answerText = buildAnswerText(question, item);
            submissions.add(new InterviewPersistenceService.AnswerSubmission(
                index, answerText, buildAnswerJson(item), question.isChoiceQuestion()));
            questions.set(index, question.withAnswer(answerText));
        });

        persistenceService.saveWholePaperAnswers(sessionId, questions, submissions);
        sessionCache.updateQuestions(sessionId, questions);
        sessionCache.updateCurrentIndex(sessionId, questions.size());
        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);
        enqueueEvaluationTask(sessionId);

        int blankCount = (int) submissions.stream()
            .filter(submission -> submission.answerText() == null || submission.answerText().isBlank())
            .count();
        log.info("会话 {} 整卷交卷: 提交={} 题, 空答案={} 题, 总题数={}",
            sessionId, submitted.size(), blankCount, questions.size());

        return new BatchSubmitAnswerResponse(
            sessionId,
            submitted.size(),
            questions.size(),
            blankCount,
            SessionStatus.COMPLETED.name(),
            AsyncTaskStatus.PENDING.name()
        );
    }

    private String normalizeAnswer(String answer) {
        return answer == null ? "" : answer.trim();
    }

    /**
     * 选择题以所选选项 key 作为作答文本（如 "BD"），其他题型用文本答案。
     */
    private String buildAnswerText(InterviewQuestionDTO question,
                                   BatchSubmitAnswerRequest.AnswerItem item) {
        List<String> keys = item.selectedKeys();
        if (question.isChoiceQuestion() && keys != null && !keys.isEmpty()) {
            return keys.stream()
                .filter(key -> key != null && !key.isBlank())
                .map(key -> key.trim().toUpperCase())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining());
        }
        return normalizeAnswer(item.answer());
    }

    /**
     * 结构化作答落库：{"selectedKeys":[...],"text":"..."}
     */
    private String buildAnswerJson(BatchSubmitAnswerRequest.AnswerItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("selectedKeys", item.selectedKeys() != null ? item.selectedKeys() : List.of());
        payload.put("text", item.answer() != null ? item.answer() : "");
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("结构化作答序列化失败，仅保存文本答案: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 暂存答案（不进入下一题）
     */
    public void saveAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId(), questions);

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.IN_PROGRESS);
        }

        // 保存答案到数据库（不更新currentIndex）
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null
            );
            persistenceService.updateSessionStatus(request.sessionId(),
                PracticeSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (Exception e) {
            log.warn("暂存答案到数据库失败: {}", e.getMessage());
        }

        log.info("会话 {} 暂存答案: 问题{}", request.sessionId(), index);
    }

    /**
     * 提前交卷（触发异步评估）
     */
    public void completeInterview(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() == SessionStatus.COMPLETED || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        // 更新 Redis 缓存
        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        // 更新数据库状态
        try {
            persistenceService.updateSessionStatus(sessionId,
                PracticeSessionEntity.SessionStatus.COMPLETED);
            // 设置评估状态为 PENDING
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        } catch (Exception e) {
            log.warn("更新会话状态失败: {}", e.getMessage());
        }

        // 发送评估任务到 Redis Stream
        evaluateStreamProducer.sendEvaluateTask(sessionId);

        log.info("会话 {} 提前交卷，评估任务已入队", sessionId);
    }

    /**
     * 获取或恢复会话（优先从缓存获取）
     */
    private CachedSession getOrRestoreSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            // 刷新 TTL
            sessionCache.refreshSessionTTL(sessionId);
            return cachedOpt.get();
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        return restoredSession;
    }

    /**
     * 生成评估报告
     */
    public InterviewReportDTO generateReport(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() != SessionStatus.COMPLETED && session.getStatus() != SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }

        log.info("生成面试报告: {}", sessionId);

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        // 获取 LLM 客户端
        String provider = null;
        Optional<PracticeSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
        if (entityOpt.isPresent()) {
            provider = entityOpt.get().getLlmProvider();
        }
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

        InterviewReportDTO report = evaluationService.evaluateInterview(
            chatClient,
            sessionId,
            session.getResumeText(),
            questions
        );

        // 更新 Redis 缓存状态
        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);

        // 保存报告到数据库
        try {
            persistenceService.saveReport(sessionId, report);
        } catch (Exception e) {
            log.warn("保存报告到数据库失败: {}", e.getMessage());
        }

        return report;
    }

    /**
     * 将缓存会话转换为 DTO
     */
    private InterviewSessionDTO toDTO(CachedSession session) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        return new InterviewSessionDTO(
            session.getSessionId(),
            session.getResumeText(),
            questions.size(),
            session.getCurrentIndex(),
            questions,
            session.getStatus(),
            session.getKnowledgeBaseId(),
            session.getSubjectCategory(),
            session.getPaperTree(objectMapper),
            session.getResumeId()
        );
    }
}
