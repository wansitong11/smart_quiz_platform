package interview.guide.modules.interview.service;

import interview.guide.common.ai.VisionRequestRejectedException;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.service.VisualQuestionEvaluationService.VisualGradeDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批改入口：只负责题型路由与委托，具体判分、报告组装和上下文拼装由独立组件承担。
 */
@Service
public class AnswerEvaluationService {

  private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

  private final UnifiedEvaluationService unifiedEvaluationService;
  private final InterviewPersistenceService persistenceService;
  private final InterviewSkillService skillService;
  private final ObjectiveAnswerGrader objectiveAnswerGrader;
  private final VisualQuestionEvaluationService visualQuestionEvaluationService;

  public AnswerEvaluationService(UnifiedEvaluationService unifiedEvaluationService,
                                 InterviewPersistenceService persistenceService,
                                 InterviewSkillService skillService,
                                 ObjectiveAnswerGrader objectiveAnswerGrader,
                                 VisualQuestionEvaluationService visualQuestionEvaluationService) {
    this.unifiedEvaluationService = unifiedEvaluationService;
    this.persistenceService = persistenceService;
    this.skillService = skillService;
    this.objectiveAnswerGrader = objectiveAnswerGrader;
    this.visualQuestionEvaluationService = visualQuestionEvaluationService;
  }

  /**
   * 评估整份作答并生成报告。
   * <p>
   * 确定性判分不调用模型；其余题目按是否需要结合原图分流到视觉批改或文本批改。
   * 所有结果最终交给 {@link ReportAssembler} 合并，入口本身不处理分值与文案细节。
   * </p>
   */
  public InterviewReportDTO evaluateInterview(ChatClient chatClient, String sessionId, String resumeText,
                                               List<InterviewQuestionDTO> questions) {
    log.info("开始评估面试: {}, 共{}题", sessionId, questions.size());
    try {
      Map<Integer, ObjectiveAnswerGrader.Grade> objectiveGrades = new LinkedHashMap<>();
      List<InterviewQuestionDTO> visualQuestions = new ArrayList<>();
      List<InterviewQuestionDTO> textQuestions = new ArrayList<>();
      List<InterviewQuestionDTO> pendingAnswerKeyQuestions = new ArrayList<>();

      for (InterviewQuestionDTO question : questions) {
        if (question.isDeterministicGrading() && !question.hasUsableAnswerKey()) {
          pendingAnswerKeyQuestions.add(question);
          continue;
        }
        objectiveAnswerGrader.grade(question).ifPresentOrElse(
            grade -> objectiveGrades.put(question.questionIndex(), grade),
            () -> {
              if (question.requiresVisualGrading()) {
                visualQuestions.add(question);
              } else {
                textQuestions.add(question);
              }
            }
        );
      }

      EvaluationInputs inputs = loadEvaluationInputs(sessionId, textQuestions, visualQuestions);
      Map<Integer, VisualGradeDTO> visualGrades = gradeVisualQuestions(
          inputs.paperId(), inputs.paperTree(), visualQuestions);
      EvaluationReport llmReport = evaluateTextQuestions(
          chatClient, sessionId, resumeText, questions, textQuestions, inputs.paperTree());

      log.info("批改分流结果: sessionId={}, 客观题={}, 视觉题={}, 文本题={}, 待答案键={}",
          sessionId, objectiveGrades.size(), visualGrades.size(), textQuestions.size(),
          pendingAnswerKeyQuestions.size());
      return ReportAssembler.withQuestionReferences(
          ReportAssembler.mergeReports(sessionId, questions, objectiveGrades, visualGrades,
              pendingAnswerKeyQuestions, llmReport),
          questions);
    } catch (BusinessException e) {
      throw e;
    } catch (VisionRequestRejectedException e) {
      throw e;
    } catch (Exception e) {
      log.error("面试评估失败: {}", ErrorLogSanitizer.summarize(e),
          ErrorLogSanitizer.forLogging(e));
      throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "面试评估失败");
    }
  }

  private EvaluationInputs loadEvaluationInputs(String sessionId,
                                                 List<InterviewQuestionDTO> textQuestions,
                                                 List<InterviewQuestionDTO> visualQuestions) {
    if (textQuestions.isEmpty() && visualQuestions.isEmpty()) {
      return new EvaluationInputs(null, null);
    }
    PaperTree paperTree = persistenceService.findPaperTree(sessionId).orElse(null);
    Long paperId = visualQuestions.isEmpty()
        ? null
        : persistenceService.findBySessionId(sessionId)
            .map(session -> session.getResumeId())
            .orElse(null);
    return new EvaluationInputs(paperTree, paperId);
  }

  private Map<Integer, VisualGradeDTO> gradeVisualQuestions(
      Long paperId, PaperTree paperTree, List<InterviewQuestionDTO> visualQuestions) {
    if (visualQuestions.isEmpty()) {
      return Map.of();
    }
    Map<Integer, List<String>> assetIdsByQuestion = paperTree == null
        ? Map.of()
        : EvaluationContextBuilder.collectQuestionAssetIds(paperTree);
    Map<Integer, VisualGradeDTO> grades = new LinkedHashMap<>();
    for (InterviewQuestionDTO question : visualQuestions) {
      if (paperId == null) {
        grades.put(question.questionIndex(), VisualGradeDTO.manualReview(
            question.questionIndex(), "缺少试卷原图，无法自动判断，请人工复核。"));
        continue;
      }
      try {
        grades.put(question.questionIndex(), visualQuestionEvaluationService.grade(
            paperId, question, assetIdsByQuestion.getOrDefault(question.questionIndex(), List.of())));
      } catch (VisionRequestRejectedException e) {
        throw e;
      } catch (Exception e) {
        log.warn("图形题批改失败，转为人工复核: session paperId={}, questionIndex={}, error={}",
            paperId, question.questionIndex(), ErrorLogSanitizer.summarize(e),
            ErrorLogSanitizer.forLogging(e));
        grades.put(question.questionIndex(), VisualGradeDTO.manualReview(
            question.questionIndex(), "图形题自动批改失败，请人工复核。"));
      }
    }
    return grades;
  }

  private EvaluationReport evaluateTextQuestions(
      ChatClient chatClient, String sessionId, String resumeText,
      List<InterviewQuestionDTO> allQuestions, List<InterviewQuestionDTO> textQuestions,
      PaperTree paperTree) {
    if (textQuestions.isEmpty()) {
      log.info("本次没有需要文本模型批改的题目: sessionId={}", sessionId);
      return null;
    }
    String referenceContext = EvaluationContextBuilder.buildQuestionReferenceContext(
        allQuestions, paperTree);
    if (referenceContext.isBlank()) {
      referenceContext = skillService.buildEvaluationReferenceSectionSafe(
          persistenceService.findBySessionId(sessionId)
              .map(session -> session.getSkillId())
              .orElse(null)
      );
    }
    List<QaRecord> qaRecords = textQuestions.stream()
        .map(question -> new QaRecord(
            question.questionIndex(), question.question(), question.category(),
            question.userAnswer(), question.isFollowUp(), question.parentQuestionIndex()))
        .toList();
    return unifiedEvaluationService.evaluate(
        chatClient, sessionId, qaRecords, resumeText, referenceContext);
  }

  /**
   * 兼容既有测试与调用方，实际裁剪规则由报告组装器统一维护。
   */
  static String limitFeedback(String feedback) {
    return ReportAssembler.limitFeedback(feedback);
  }

  private record EvaluationInputs(PaperTree paperTree, Long paperId) {
  }
}
