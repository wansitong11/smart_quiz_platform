package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.StreamWorkerRegistry;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.quiz.service.WrongQuestionBookService;
import interview.guide.modules.resume.model.PracticePaperEntity;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/**
 * 面试评估 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行评估
 */
@Slf4j
@Component
public class EvaluateStreamConsumer extends AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload> {

    private final InterviewSessionRepository sessionRepository;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final LlmProviderRegistry llmProviderRegistry;
    private final WrongQuestionBookService wrongQuestionBookService;

    public EvaluateStreamConsumer(
        RedisService redisService,
        StreamWorkerRegistry workerRegistry,
        InterviewSessionRepository sessionRepository,
        AnswerEvaluationService evaluationService,
        InterviewPersistenceService persistenceService,
        ObjectMapper objectMapper,
        LlmProviderRegistry llmProviderRegistry,
        WrongQuestionBookService wrongQuestionBookService
    ) {
        super(redisService, workerRegistry);
        this.sessionRepository = sessionRepository;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
        this.wrongQuestionBookService = wrongQuestionBookService;
    }

    record EvaluatePayload(String sessionId, String attemptId) {
        EvaluatePayload(String sessionId) {
            this(sessionId, UUID.randomUUID().toString());
        }
    }

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String consumerKey() {
        return "interview-evaluate";
    }

    @Override
    protected String threadName() {
        return "evaluate-consumer";
    }

    @Override
    protected EvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new EvaluatePayload(sessionId, UUID.randomUUID().toString());
    }

    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected boolean shouldSkip(EvaluatePayload payload) {
        return sessionRepository.findBySessionId(payload.sessionId())
            .map(session -> session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED)
            .orElse(true);
    }

    @Override
    protected void markProcessing(EvaluatePayload payload) {
        // 条件领取由 tryMarkProcessing 完成。
    }

    @Override
    protected boolean tryMarkProcessing(EvaluatePayload payload) {
        return sessionRepository.tryMarkEvaluateProcessing(
            payload.sessionId(), payload.attemptId(), LocalDateTime.now()) == 1;
    }

    @Override
    protected void processBusiness(EvaluatePayload payload) {
        String sessionId = payload.sessionId();
        heartbeat(payload);
        Optional<PracticeSessionEntity> sessionOpt = sessionRepository.findBySessionIdWithResume(sessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("会话已被删除，跳过评估任务: sessionId={}", sessionId);
            return;
        }

        PracticeSessionEntity session = sessionOpt.get();
        assertPaperContentRevision(session);
        List<InterviewQuestionDTO> questions = PaperTree.parse(objectMapper, session.getPaperJson())
            .flattenQuestions();

        List<PracticeAnswerEntity> answers = persistenceService.findAnswersBySessionId(sessionId);
        for (PracticeAnswerEntity answer : answers) {
            int index = answer.getQuestionIndex();
            if (index >= 0 && index < questions.size()) {
                InterviewQuestionDTO question = questions.get(index);
                questions.set(index, question.withAnswer(answer.getUserAnswer()));
            }
        }

        // 获取 LLM 客户端
        String provider = session.getLlmProvider();
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

        String resumeText = session.getResume() != null ? session.getResume().getResumeText() : "";
        InterviewReportDTO report = evaluationService.evaluateInterview(chatClient, sessionId, resumeText, questions);
        persistenceService.saveReport(sessionId, report);
        heartbeat(payload);
        wrongQuestionBookService.recordAttemptResults(sessionId);
    }

    private void heartbeat(EvaluatePayload payload) {
        sessionRepository.heartbeatEvaluateProcessing(
            payload.sessionId(), payload.attemptId(), LocalDateTime.now());
    }

  static void assertPaperContentRevision(PracticeSessionEntity session) {
    PracticePaperEntity paper = session.getResume();
    if ("PAPER".equals(session.getSourceType())
        && paper != null
        && session.getPaperContentRevision() != null
        && !Objects.equals(session.getPaperContentRevision(), paper.getContentRevision())) {
      throw new BusinessException(
          ErrorCode.PAPER_CONTENT_CHANGED,
          "试卷内容已更新，无法继续自动批改，请基于最新内容重新开始作答"
      );
    }
  }

    @Override
    protected void markCompleted(EvaluatePayload payload) {
        sessionRepository.completeEvaluateIfProcessing(payload.sessionId());
    }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) {
        sessionRepository.failEvaluateIfProcessing(
            payload.sessionId(), truncateError(error));
    }

    @Override
    protected void retryMessage(EvaluatePayload payload, int retryCount) {
        String sessionId = payload.sessionId();
        int reset = sessionRepository.resetEvaluateToPending(sessionId);
        if (reset != 1) {
            log.warn("评估重试重置失败，ACK 丢弃: sessionId={}, retryCount={}",
                sessionId, retryCount);
            return;
        }
        try {
            Map<String, String> message = new LinkedHashMap<>();
            message.put(AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId);
            message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
            message.putAll(aiContextMessageFields());

            redisService().streamAdd(
                AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("评估任务已重新入队: sessionId={}, retryCount={}", sessionId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            sessionRepository.failEvaluateIfPending(
                sessionId, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

}
