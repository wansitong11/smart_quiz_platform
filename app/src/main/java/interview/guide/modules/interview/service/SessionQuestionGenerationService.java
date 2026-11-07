package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.model.QuestionGenRequestSnapshot;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步出题 worker：读取壳会话里的请求快照，生成题目并原子写回会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionQuestionGenerationService {

    private final InterviewQuestionService questionService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;

    public void generate(String sessionId, String attemptId) {
        PracticeSessionEntity entity = sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "答题记录不存在"));
        QuestionGenRequestSnapshot snapshot = readSnapshot(entity.getQuestionGenRequestJson());

        heartbeat(sessionId, attemptId);
        List<HistoricalQuestion> history =
            persistenceService.getHistoricalQuestions(snapshot.skillId(), snapshot.resumeId());
        List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
            snapshot.llmProvider(),
            snapshot.skillId(),
            snapshot.difficulty(),
            snapshot.resumeText(),
            snapshot.questionCount(),
            history,
            snapshot.customCategories(),
            snapshot.jdText()
        );
        heartbeat(sessionId, attemptId);

        PaperTree tree = PaperTree.fromFlatQuestions(questions, snapshot.skillId());
        int updated = sessionRepository.completeQuestionGenIfProcessing(
            sessionId, attemptId, tree.toJson(objectMapper), questions.size(), LocalDateTime.now());
        if (updated == 1) {
            try {
                sessionCache.saveSession(
                    sessionId,
                    snapshot.resumeText() != null ? snapshot.resumeText() : "",
                    snapshot.resumeId(),
                    null,
                    null,
                    tree,
                    0,
                    SessionStatus.CREATED
                );
            } catch (Exception e) {
                // DB 已经是完成态，缓存刷新失败不能反向把任务标记为失败。
                log.warn("刷新异步出题缓存失败，读取时将回源数据库: sessionId={}", sessionId, e);
            }
            log.info("异步出题完成: sessionId={}, questions={}", sessionId, questions.size());
        } else {
            log.info("异步出题结果已失效（状态已被其他代次推进）: sessionId={}, attemptId={}",
                sessionId, attemptId);
        }
    }

    private void heartbeat(String sessionId, String attemptId) {
        int updated = sessionRepository.heartbeatQuestionGenProcessing(
            sessionId, attemptId, LocalDateTime.now());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "出题任务执行权已失效");
        }
    }

    private QuestionGenRequestSnapshot readSnapshot(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "出题请求快照缺失");
        }
        try {
            return objectMapper.readValue(json, QuestionGenRequestSnapshot.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "出题请求快照解析失败", e);
        }
    }
}
