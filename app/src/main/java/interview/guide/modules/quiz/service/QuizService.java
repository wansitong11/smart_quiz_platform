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
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.quiz.config.QuizProperties;
import interview.guide.modules.quiz.model.QuizConfig;
import interview.guide.modules.quiz.model.QuizConfigRequest;
import interview.guide.modules.quiz.model.WrongQuestionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
  private final InterviewSkillService skillService;
  private final QuizProperties properties;
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

  // ==================== 错题本 ====================

  /**
   * 错题本：扫描已完成的会话，按「得分 < 该题满分」判定错题，同题聚合错误次数。
   * <p>
   * 不新增表也不改批改链路：满分来自会话的 PaperTree（题目自带 fullScore），
   * 得分来自 practice_answers.score，两者在查询时关联即可。
   */
  public List<WrongQuestionDTO> listWrongQuestions(String skillId, Integer limit) {
    int max = limit == null || limit <= 0 ? 100 : Math.min(limit, 300);

    List<PracticeSessionEntity> sessions = persistenceService.findAll().stream()
        .filter(entity -> entity.getStatus() == PracticeSessionEntity.SessionStatus.COMPLETED
            || entity.getStatus() == PracticeSessionEntity.SessionStatus.EVALUATED)
        .filter(entity -> skillId == null || skillId.isBlank() || skillId.equals(entity.getSkillId()))
        .sorted(Comparator.comparing(PracticeSessionEntity::getCreatedAt,
            Comparator.nullsLast(Comparator.naturalOrder())).reversed())
        .toList();

    Map<String, WrongQuestionDTO> aggregated = new LinkedHashMap<>();
    for (PracticeSessionEntity session : sessions) {
      PaperTree tree = persistenceService.findPaperTree(session.getSessionId()).orElse(null);
      if (tree == null) {
        continue;
      }
      Map<Integer, InterviewQuestionDTO> questions = new LinkedHashMap<>();
      tree.flattenQuestions().forEach(question -> questions.put(question.questionIndex(), question));

      for (PracticeAnswerEntity answer : persistenceService.findAnswersBySessionId(session.getSessionId())) {
        InterviewQuestionDTO question = questions.get(answer.getQuestionIndex());
        if (question == null || answer.getScore() == null) {
          continue;
        }
        int fullScore = question.fullScore() == null ? 0 : question.fullScore();
        if (fullScore <= 0 || answer.getScore() >= fullScore) {
          continue;
        }
        String key = session.getSkillId() + "|" + normalizeStem(question.question());
        WrongQuestionDTO previous = aggregated.get(key);
        int wrongCount = previous == null ? 1 : previous.wrongCount() + 1;
        LocalDateTime answeredAt = answer.getAnsweredAt();
        aggregated.put(key, new WrongQuestionDTO(
            session.getSessionId(),
            question.questionIndex(),
            session.getSkillId(),
            question.category(),
            question.questionType(),
            question.question(),
            answer.getUserAnswer(),
            answer.getReferenceAnswer() != null ? answer.getReferenceAnswer() : question.referenceAnswer(),
            question.analysis(),
            answer.getScore(),
            fullScore,
            wrongCount,
            answeredAt,
            question));
      }
    }

    return aggregated.values().stream()
        .sorted(Comparator.comparingInt(WrongQuestionDTO::wrongCount).reversed()
            .thenComparing(WrongQuestionDTO::lastWrongAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed())
        .limit(max)
        .toList();
  }

  /** 错题来源引用：由前端回传 sessionId + questionIndex，后端以库中数据为准重建，避免信任前端题目内容 */
  public record WrongQuestionRef(String sessionId, Integer questionIndex) {
  }

  /**
   * 错题重练：用选中的错题直接组一份新测验（不调模型，秒级完成）。
   */
  public InterviewSessionDTO retryWrongQuestions(List<WrongQuestionRef> refs) {
    if (refs == null || refs.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请先选择要重练的错题");
    }
    int limit = Math.min(refs.size(), properties.getMaxQuestions());

    List<InterviewQuestionDTO> questions = new ArrayList<>();
    String skillId = null;
    String subjectLabel = null;
    int index = 0;
    for (WrongQuestionRef ref : refs) {
      if (questions.size() >= limit || ref == null || ref.sessionId() == null
          || ref.questionIndex() == null) {
        continue;
      }
      PracticeSessionEntity session = persistenceService.findBySessionId(ref.sessionId()).orElse(null);
      if (session == null) {
        continue;
      }
      PaperTree tree = persistenceService.findPaperTree(ref.sessionId()).orElse(null);
      if (tree == null) {
        continue;
      }
      InterviewQuestionDTO origin = tree.flattenQuestions().stream()
          .filter(question -> ref.questionIndex().equals(question.questionIndex()))
          .findFirst()
          .orElse(null);
      if (origin == null) {
        continue;
      }
      if (skillId == null) {
        skillId = session.getSkillId();
        subjectLabel = session.getSubjectCategory();
      }
      questions.add(withIndex(origin, index++));
    }

    if (questions.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "所选错题已失效，请刷新后重试");
    }

    PaperTree tree = PaperTree.fromFlatQuestions(questions, "错题重练");
    InterviewSessionDTO session = sessionService.createSessionFromQuestions(
        questions,
        tree,
        null,
        skillId,
        "mid",
        null,
        subjectLabel,
        null,
        SOURCE_QUIZ_WRONG);
    log.info("错题重练已创建: sessionId={}, 题数={}", session.sessionId(), questions.size());
    return session;
  }

  // ==================== 工具 ====================

  private InterviewQuestionDTO withIndex(InterviewQuestionDTO origin, int index) {
    return new InterviewQuestionDTO(
        index,
        origin.question(),
        origin.type(),
        origin.category(),
        origin.topicSummary(),
        null,
        null,
        null,
        false,
        null,
        origin.referenceAnswer(),
        origin.keyPoints(),
        origin.scoringRubric(),
        origin.sourceContext(),
        origin.fullScore(),
        origin.questionType(),
        origin.options(),
        origin.analysis());
  }

  private String writeConfig(QuizConfig config) {
    return objectMapper.writeValueAsString(config);
  }

  private String normalizeStem(String stem) {
    if (stem == null) {
      return "";
    }
    return stem.replaceAll("\\s+", "").trim();
  }
}
