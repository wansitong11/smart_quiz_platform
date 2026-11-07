package interview.guide.modules.quiz.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.ai.AiCost;
import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiCostControl;
import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.result.Result;
import interview.guide.modules.interview.model.BatchSubmitAnswerRequest;
import interview.guide.modules.interview.model.BatchSubmitAnswerResponse;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.SessionListItemDTO;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.quiz.model.QuizCatalogDTO;
import interview.guide.modules.quiz.model.QuizConfigRequest;
import interview.guide.modules.quiz.model.QuizSubjectRequest;
import interview.guide.modules.quiz.model.WrongQuestionFilter;
import interview.guide.modules.quiz.model.WrongQuestionPageDTO;
import interview.guide.modules.quiz.model.WrongQuestionRetryRequest;
import interview.guide.modules.quiz.model.WrongQuestionStatus;
import interview.guide.modules.quiz.model.WrongQuestionStatusRequest;
import interview.guide.modules.quiz.service.QuizCatalogService;
import interview.guide.modules.quiz.service.QuizService;
import interview.guide.modules.quiz.service.QuizSubjectService;
import interview.guide.modules.quiz.service.WrongQuestionBookService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 模拟测验接口。
 * <p>
 * 组卷、作答、批改全部复用 interview 模块的能力，这里只提供「测验」语义的入口：
 * 按学科组卷、整卷交卷、报告、错题本与再测一次。
 */
@Slf4j
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
@Tag(name = "模拟测验", description = "按学科组卷、整卷作答、批改报告、错题本")
public class QuizController {

  private final QuizService quizService;
  private final InterviewSessionService sessionService;
  private final InterviewHistoryService historyService;
  private final InterviewPersistenceService persistenceService;
  private final InterviewSkillService skillService;
  private final QuizCatalogService catalogService;
  private final QuizSubjectService subjectService;
  private final WrongQuestionBookService wrongQuestionBookService;
  private final AiCostControl aiCostControl;

  /** 考试 → 科目 → 考点 → 题型目录（系统科目 + 我的科目） */
  @GetMapping("/catalog")
  public Result<QuizCatalogDTO> catalog() {
    return Result.success(catalogService.catalog());
  }

  /** 学科与知识板块（供组卷配置面板渲染） */
  @GetMapping("/subjects")
  public Result<List<InterviewSkillService.SkillDTO>> listSubjects() {
    List<InterviewSkillService.SkillDTO> subjects = skillService.getAllSkills().stream()
        .filter(InterviewSkillService.SkillDTO::isPreset)
        .toList();
    return Result.success(subjects);
  }

  /** 新建自定义科目 */
  @PostMapping("/subjects")
  public Result<QuizCatalogDTO.Subject> createSubject(
      @Valid @RequestBody QuizSubjectRequest request) {
    return Result.success(subjectService.create(request));
  }

  /** 编辑自定义科目 */
  @PutMapping("/subjects/{subjectCode}")
  public Result<QuizCatalogDTO.Subject> updateSubject(
      @PathVariable String subjectCode,
      @Valid @RequestBody QuizSubjectRequest request) {
    return Result.success(subjectService.update(subjectCode, request));
  }

  /** 软删除自定义科目 */
  @DeleteMapping("/subjects/{subjectCode}")
  public Result<Void> deleteSubject(@PathVariable String subjectCode) {
    subjectService.delete(subjectCode);
    return Result.success();
  }

  /** 复制内置科目为我的科目 */
  @PostMapping("/subjects/clone")
  public Result<QuizCatalogDTO.Subject> cloneSubject(
      @Valid @RequestBody CloneSubjectRequest request) {
    return Result.success(subjectService.clone(request.sourceCode()));
  }

  /** 创建模拟测验（组卷） */
  @PostMapping("/sessions")
  @AiCost(feature = AiFeature.QUIZ_GENERATE, operation = "create")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<InterviewSessionDTO> createQuiz(@Valid @RequestBody QuizConfigRequest request) {
    log.info("创建模拟测验: skill={}, 题量={}, 板块={}",
        request.skillId(), request.questionCount(), request.categories());
    AiCallContext context = AiCallContextHolder.get();
    aiCostControl.reserveOrThrow(
        context == null ? null : context.operationId(),
        context == null ? null : context.userId(),
        AiFeature.QUIZ_GENERATE,
        new AiCostEstimateInput(null,
            request.questionCount() == null ? 8L : request.questionCount().longValue(),
            null, null, null));
    try {
      return Result.success(quizService.createQuiz(request));
    } finally {
      if (context != null) {
        aiCostControl.finalize(context.operationId());
      }
    }
  }

  /** 会话详情（含试卷层级树） */
  @GetMapping("/sessions/{sessionId}")
  public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
    return Result.success(sessionService.getSession(sessionId));
  }

  /** 整卷交卷（一次提交全部答案，后端触发批改） */
  @PostMapping("/sessions/{sessionId}/submit")
  @AiCost(feature = AiFeature.ANSWER_EVALUATE, operation = "submit")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<BatchSubmitAnswerResponse> submit(@PathVariable String sessionId,
      @Valid @RequestBody BatchSubmitAnswerRequest request) {
    log.info("模拟测验交卷: sessionId={}, 题数={}", sessionId, request.answers().size());
    AiCallContext context = AiCallContextHolder.get();
    aiCostControl.reserveOrThrow(
        context == null ? null : context.operationId(),
        context == null ? null : context.userId(),
        AiFeature.ANSWER_EVALUATE,
        new AiCostEstimateInput(null, (long) request.answers().size(), null, null, null));
    try {
      return Result.success(sessionService.submitWholePaper(sessionId, request.answers()));
    } catch (RuntimeException e) {
      if (context != null) {
        aiCostControl.finalize(context.operationId());
      }
      throw e;
    }
  }

  /** 报告详情（含逐题对错与解析） */
  @GetMapping("/sessions/{sessionId}/details")
  public Result<InterviewDetailDTO> details(@PathVariable String sessionId) {
    return Result.success(historyService.getInterviewDetail(sessionId));
  }

  /** 导出报告 PDF */
  @GetMapping("/sessions/{sessionId}/export")
  public ResponseEntity<byte[]> export(@PathVariable String sessionId) {
    byte[] pdf = historyService.exportInterviewPdf(sessionId);
    String filename = URLEncoder.encode("模拟测验报告-" + sessionId + ".pdf", StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }

  /** 测验记录 */
  @GetMapping("/history")
  public Result<List<SessionListItemDTO>> history() {
    // 只返回模拟测验与错题重练：试卷分析（PAPER）、资料出题（KNOWLEDGE_BASE）
    // 等会话来路不通，混在一起会让学生分不清"我做的试卷"和"系统出的测验"
    return Result.success(persistenceService.findAll().stream()
        .filter(entity -> QuizService.SOURCE_QUIZ.equals(entity.getSourceType())
            || QuizService.SOURCE_QUIZ_WRONG.equals(entity.getSourceType()))
        .map(SessionListItemDTO::from)
        .toList());
  }

  /** 再测一次：用上次的组卷参数重新出题 */
  @PostMapping("/sessions/{sessionId}/retry")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<InterviewSessionDTO> retry(@PathVariable String sessionId) {
    return Result.success(quizService.retryQuiz(sessionId));
  }

  @DeleteMapping("/sessions/{sessionId}")
  public Result<Void> delete(@PathVariable String sessionId) {
    persistenceService.deleteSessionBySessionId(sessionId);
    return Result.success();
  }

  /** 错题本 */
  @GetMapping("/wrong-questions")
  public Result<WrongQuestionPageDTO> wrongQuestions(
      @RequestParam(required = false) String examCode,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String skillId,
      @RequestParam(required = false) String knowledgePointKey,
      @RequestParam(required = false) String questionType,
      @RequestParam(required = false) WrongQuestionStatus status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Integer limit) {
    String subject = subjectCode == null || subjectCode.isBlank() ? skillId : subjectCode;
    int pageSize = limit == null ? size : limit;
    return Result.success(wrongQuestionBookService.list(
        new WrongQuestionFilter(examCode, subject, knowledgePointKey, questionType, status, keyword),
        page, pageSize));
  }

  /** 错题重练 */
  @PostMapping("/wrong-questions/retry")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<InterviewSessionDTO> retryWrongQuestions(
      @Valid @RequestBody WrongQuestionRetryRequest request) {
    return Result.success(wrongQuestionBookService.retry(request));
  }

  /** 手动移出/恢复错题 */
  @PutMapping("/wrong-questions/{questionKey}/status")
  public Result<Void> updateWrongQuestionStatus(
      @PathVariable String questionKey,
      @Valid @RequestBody WrongQuestionStatusRequest request) {
    wrongQuestionBookService.updateStatus(questionKey, request.status());
    return Result.success();
  }

  public record CloneSubjectRequest(
      @NotBlank(message = "源科目不能为空") String sourceCode) {
  }
}
