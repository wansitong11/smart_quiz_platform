package interview.guide.modules.quiz.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.quiz.model.QuizConfig;
import interview.guide.modules.quiz.model.QuizConfigRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 模拟测验：组卷编排、错题本、再测一次。
 * <p>
 * 生成与作答/批改全部复用 interview 模块的能力，本服务只做「教育语义」的编排：
 * 组卷参数 → PaperTree → 会话；错题聚合 → 错题重练。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizService {

  /** 模拟测验来源标记（practice_sessions.source_type） */
  public static final String SOURCE_QUIZ = "QUIZ";
  /** 错题重练来源标记 */
  public static final String SOURCE_QUIZ_WRONG = "QUIZ_WRONG";

  private final QuizPaperService paperService;
  private final InterviewSessionService sessionService;
  private final InterviewPersistenceService persistenceService;
  private final ObjectMapper objectMapper;

  /**
   * 创建一次模拟测验：组卷参数 → 出题 → 落库会话。
   */
  public InterviewSessionDTO createQuiz(QuizConfigRequest request) {
    QuizConfig config = new QuizConfig(
        request.skillId(),
        request.difficulty() == null ? "mid" : request.difficulty(),
        request.questionCount() == null ? 8 : request.questionCount(),
        request.categories() == null ? List.of() : request.categories(),
        request.questionTypes() == null ? List.of() : request.questionTypes(),
        request.llmProvider());

    QuizPaperService.GeneratedQuiz generated = paperService.generate(config);

    InterviewSessionDTO session = sessionService.createSessionFromQuestions(
        generated.questions(),
        generated.paperTree(),
        config.llmProvider(),
        config.skillId(),
        config.difficulty(),
        null,
        generated.subjectLabel(),
        null,
        SOURCE_QUIZ);

    try {
        persistenceService.updateQuizConfig(session.sessionId(), writeConfig(config));
    } catch (Exception e) {
        // 组卷已经成功，配置快照写失败只影响「再测一次」，不影响本次作答
        log.warn("保存组卷参数快照失败: sessionId={}, error={}", session.sessionId(), e.toString());
    }

    log.info("模拟测验已创建: sessionId={}, skill={}, 题数={}, warnings={}",
        session.sessionId(), config.skillId(), generated.questions().size(), generated.warnings());
    return session;
  }

  /**
   * 原题重测：直接复用同一份卷子另开一次作答，**不调用模型**（秒级）。
   * <p>
   * 与「重新组卷」的区别：题目、选项、分值、题号索引全部保持一致，
   * 学生可以在同一套题上二刷、对照上次的错题。
   */
  public InterviewSessionDTO retryQuiz(String sessionId) {
    PracticeSessionEntity entity = persistenceService.findBySessionId(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "测验不存在"));

    PaperTree tree = persistenceService.findPaperTree(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
            "这次测验没有可复用的卷子"));
    List<InterviewQuestionDTO> questions = tree.flattenQuestions();
    if (questions.isEmpty()) {
        throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT,
            "这次测验没有题目，无法重测");
    }

    InterviewSessionDTO session = sessionService.createSessionFromQuestions(
        questions,
        tree,
        entity.getLlmProvider(),
        entity.getSkillId(),
        entity.getDifficulty(),
        null,
        entity.getSubjectCategory(),
        null,
        SOURCE_QUIZ);

    // 沿用同一份组卷参数快照，保持记录完整（失败不影响本次重测）
    if (entity.getQuizConfigJson() != null && !entity.getQuizConfigJson().isBlank()) {
        try {
            persistenceService.updateQuizConfig(session.sessionId(), entity.getQuizConfigJson());
        } catch (Exception e) {
            log.warn("复制组卷参数快照失败: {}", e.toString());
        }
    }

    log.info("原题重测已创建: 原 sessionId={}, 新 sessionId={}, 题数={}",
        sessionId, session.sessionId(), questions.size());
    return session;
  }

  private String writeConfig(QuizConfig config) {
    return objectMapper.writeValueAsString(config);
  }
}
