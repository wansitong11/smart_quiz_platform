package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.AnswerKeySupport;
import interview.guide.common.ai.RetryableFailureClassifier;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.VisionFailureClass;
import interview.guide.common.ai.VisionFailureClassifier;
import interview.guide.common.ai.VisionRequestRejectedException;
import interview.guide.common.ai.VisionRetryPolicy;
import interview.guide.common.evaluation.StudentFacingFeedbackSanitizer;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.QuestionFingerprint;
import interview.guide.modules.aicost.service.FreeModelConcurrencyGate;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.log.PaperFlowLog;
import interview.guide.modules.resume.repository.PaperPageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 在结构切题完成后生成内部答案键。
 * 纯文字题走 plain chat，含图题按页分组走视觉模型；答案键不会进入学生端响应。
 */
@Slf4j
@Service
public class AnswerKeyPreparationService {

  private static final int MAX_IMAGES_PER_CALL = 10;
  private static final int MAX_QUESTIONS_PER_CALL = 1;
  private static final int MAX_ANALYSIS_CHARS = 520;
  private static final int MAX_FIGURE_SUMMARY_CHARS = 120;
  private static final int TEXT_BATCH_SIZE = 10;
  private static final int ANSWER_KEY_ATTEMPTS = 3;
  private static final String ANALYSIS_MISSING_MESSAGE = "解析未生成，请稍后重新生成。";
  private static final Pattern VISUAL_HINT_PATTERN = Pattern.compile(
      "如图|图中|下图|上图|展开图|数轴|网格|统计图|图表|示意图|补全图形");
  private static final Pattern DRAWING_HINT_PATTERN = Pattern.compile(
      "画出|补全|涂色|连接|在图中标出|在图中补充|作图");
  private static final Pattern CHOICE_ANSWER_PREFIX = Pattern.compile(
      "^(?:正确答案|参考答案|标准答案|答案|ANWSER|ANSWER|故选|应选|选择|选)\\s*[:：]?\\s*");
  private static final Pattern CHOICE_SEPARATOR = Pattern.compile(
      "(?:AND|[\\s,，、;；/／|&＋+和与及])");
  private static final Pattern CHOICE_WRAPPER = Pattern.compile(
      "[\\(\\)（）\\[\\]【】\"'“”‘’]");
  private static final String CIRCLED_NUMBERS = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳";

  private final LlmProviderRegistry providerRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PaperAssetService assetService;
  private final PaperPageRepository pageRepository;
  private final FileStorageService storageService;
  private final PageImageNormalizer imageNormalizer;
  private final PaperStructureService structureService;
  private final AnswerConsistencyResolver consistencyResolver;
  private final AnswerBatchRunner answerBatchRunner;
  private final PaperAnalysisPacer analysisPacer;
  private final PaperAnswerExecutorProperties answerProperties;
  private final FreeModelConcurrencyGate freeModelConcurrencyGate;
  private final AnalysisBatchScheduler analysisBatchScheduler;
  private final PromptTemplate textSystemPrompt;
  private final PromptTemplate textUserPrompt;
  private final PromptTemplate visualSystemPrompt;
  private final PromptTemplate visualUserPrompt;
  private final PromptTemplate analysisSystemPrompt;
  private final PromptTemplate analysisUserPrompt;
  private final PromptTemplate subjectiveReviewSystemPrompt;
  private final PromptTemplate subjectiveReviewUserPrompt;
  private final BeanOutputConverter<AnswerBatchDTO> answerOutputConverter =
      new BeanOutputConverter<>(AnswerBatchDTO.class);
  private final BeanOutputConverter<AnalysisBatchDTO> analysisOutputConverter =
      new BeanOutputConverter<>(AnalysisBatchDTO.class);
  private final BeanOutputConverter<SubjectiveReviewBatchDTO> subjectiveReviewConverter =
      new BeanOutputConverter<>(SubjectiveReviewBatchDTO.class);

  public AnswerKeyPreparationService(
      LlmProviderRegistry providerRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      PaperAssetService assetService,
      PaperPageRepository pageRepository,
      FileStorageService storageService,
      PageImageNormalizer imageNormalizer,
      PaperStructureService structureService,
      AnswerConsistencyResolver consistencyResolver,
      ResourceLoader resourceLoader,
      AnswerBatchRunner answerBatchRunner,
      PaperAnalysisPacer analysisPacer,
      PaperAnswerExecutorProperties answerProperties,
      FreeModelConcurrencyGate freeModelConcurrencyGate,
      AnalysisBatchScheduler analysisBatchScheduler
  ) throws IOException {
    this.providerRegistry = providerRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.assetService = assetService;
    this.pageRepository = pageRepository;
    this.storageService = storageService;
    this.imageNormalizer = imageNormalizer;
    this.structureService = structureService;
    this.consistencyResolver = consistencyResolver;
    this.answerBatchRunner = answerBatchRunner;
    this.analysisPacer = analysisPacer;
    this.answerProperties = answerProperties;
    this.freeModelConcurrencyGate = freeModelConcurrencyGate;
    this.analysisBatchScheduler = analysisBatchScheduler;
    this.textSystemPrompt = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-answer-key-system.st"));
    this.textUserPrompt = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-answer-key-user.st"));
    this.visualSystemPrompt = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-visual-answer-key-system.st"));
    this.visualUserPrompt = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-visual-answer-key-user.st"));
    this.analysisSystemPrompt = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-answer-analysis-system.st"));
    this.analysisUserPrompt = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-answer-analysis-user.st"));
    this.subjectiveReviewSystemPrompt = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-subjective-review-system.st"));
    this.subjectiveReviewUserPrompt = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-subjective-review-user.st"));
  }

  public record PreparedAnswerKeys(
      PaperStructureService.BuiltStructure structure,
      List<String> warnings
  ) {
  }

  record AnswerBatchDTO(List<GeneratedAnswerDTO> answers) {
  }

  record GeneratedAnswerDTO(
      int questionIndex,
      String referenceAnswer,
      List<String> selectedOptions,
      String figureSummary,
      Boolean requiresVisualGrading,
      Integer confidence,
      String derivedResult
  ) {
    GeneratedAnswerDTO(
        int questionIndex,
        String referenceAnswer,
        List<String> selectedOptions,
        String figureSummary,
        Boolean requiresVisualGrading,
        Integer confidence
    ) {
      this(questionIndex, referenceAnswer, selectedOptions, figureSummary,
          requiresVisualGrading, confidence, null);
    }
  }

  record AnalysisBatchDTO(List<GeneratedAnalysisDTO> analyses) {
  }

  record SubjectiveReviewBatchDTO(List<SubjectiveReviewDTO> reviews) {
  }

  record SubjectiveReviewDTO(
      int questionIndex,
      List<String> keyPoints,
      String scoringRubric,
      String sampleAnswer
  ) {
  }

  record GeneratedAnalysisDTO(
      int questionIndex,
      String analysis,
      List<String> keyPoints,
      String scoringRubric
  ) {
  }

  private record QuestionSource(
      InterviewQuestionDTO question,
      List<String> inheritedAssetIds,
      String context
  ) {
  }

  private record AnalysisInput(QuestionSource source, AnswerKeyDTO base) {
  }

  private record AnalysisResult(int questionIndex, AnswerKeyDTO key) {
  }

  private record AnalysisBatchResult(List<AnalysisResult> results, String warning) {
  }

  private record ModelImage(String assetId, Integer pageNo, String role) {
  }

  /**
   * 已存在完整答案键时直接复用，避免重试或重新进入页面时重复付费调用。
   */
  public PreparedAnswerKeys prepare(Long paperId, PaperStructureService.BuiltStructure structure) {
    return prepare(paperId, structure, null);
  }

  public PreparedAnswerKeys prepare(Long paperId, PaperStructureService.BuiltStructure structure,
                                    Runnable heartbeat) {
    long startNanos = System.nanoTime();
    PaperFlowLog.event("ANSWER_KEY_PREPARE_BEGIN paperId={}, questions={}, "
            + "existingAnswerKeys={}",
        paperId, structure.questions().size(), structure.questions().stream()
            .filter(this::isUsableAnswerKey).count());
    List<QuestionSource> sources = collectQuestionSources(structure.paperTree());
    List<QuestionSource> missingSources = sources.stream()
        .filter(source -> !isUsableAnswerKey(source.question()))
        .toList();
    List<String> warnings = new ArrayList<>();
    Map<Integer, AnswerKeyDTO> keys = new LinkedHashMap<>();
    for (QuestionSource source : sources) {
      if (isUsableAnswerKey(source.question())) {
        keys.put(source.question().questionIndex(), existingAnswerKey(source.question()));
      }
    }
    boolean answersComplete = missingSources.isEmpty();
    boolean analysisMissing = hasMissingAnalysis(sources, keys);
    if (answersComplete && !analysisMissing) {
      PaperFlowLog.event("ANSWER_KEY_PREPARE_END paperId={}, status=SKIPPED_COMPLETE, "
              + "questions={}, durationMs={}",
          paperId, sources.size(), elapsedMs(startNanos));
      return new PreparedAnswerKeys(structure, List.of());
    }
    if (!keys.isEmpty() && !answersComplete) {
      warnings.add("已复用 " + keys.size() + " 道题的已有答案，仅重新生成 "
          + missingSources.size() + " 道");
    }

    if (!answersComplete) {
      generateMissingAnswers(paperId, structure, sources, missingSources, keys, warnings,
          heartbeat);
    }
    enrichAnalyses(paperId, sources, keys, warnings, heartbeat);

    PaperStructureService.BuiltStructure applied =
        structureService.applyAndStoreAnswerKeys(paperId, structure, keys);
    PaperFlowLog.event("ANSWER_KEY_PREPARE_END paperId={}, status=SUCCESS, questions={}, "
            + "warnings={}, durationMs={}",
        paperId, sources.size(), warnings, elapsedMs(startNanos));
    return new PreparedAnswerKeys(applied, warnings);
  }

  private void generateMissingAnswers(
      Long paperId,
      PaperStructureService.BuiltStructure structure,
      List<QuestionSource> sources,
      List<QuestionSource> missingSources,
      Map<Integer, AnswerKeyDTO> keys,
      List<String> warnings,
      Runnable heartbeat
  ) {
    Map<Boolean, List<QuestionSource>> split = missingSources.stream()
        .collect(Collectors.partitioningBy(this::needsVision));
    List<QuestionSource> verificationSources = missingSources.stream()
        .filter(source -> requiresConsistencyVote(source.question()))
        .toList();
    Map<Boolean, List<QuestionSource>> verificationSplit = verificationSources.stream()
        .collect(Collectors.partitioningBy(this::needsVision));

    Map<Integer, AnswerKeyDTO> verificationKeys = new LinkedHashMap<>();

    List<AnswerBatchRunner.BatchTask> answerTasks = new ArrayList<>();
    int nextBatchIndex = 0;
    nextBatchIndex = addTextTasks(answerTasks, paperId,
        split.getOrDefault(false, List.of()), AnswerPhase.PRIMARY, nextBatchIndex);
    nextBatchIndex = addVisualTasks(answerTasks, paperId,
        split.getOrDefault(true, List.of()), AnswerPhase.PRIMARY, nextBatchIndex);
    nextBatchIndex = addTextTasks(answerTasks, paperId,
        verificationSplit.getOrDefault(false, List.of()), AnswerPhase.VERIFY, nextBatchIndex);
    addVisualTasks(answerTasks, paperId,
        verificationSplit.getOrDefault(true, List.of()), AnswerPhase.VERIFY, nextBatchIndex);

    List<AnswerBatchRunner.BatchOutcome> answerOutcomes =
        answerBatchRunner.run(answerTasks, AnswerBatchRunner.CancelPolicy.IGNORE_FATAL, heartbeat,
            outcome -> persistPrimaryDelta(paperId, structure, keys, warnings, outcome));
    for (AnswerBatchRunner.BatchOutcome outcome : answerOutcomes) {
      if (outcome.fatal() != null) {
        warnings.add("部分答案键批次失败，已保留其他批次结果："
            + safeMessage(outcome.fatal()));
        continue;
      }
      Map<Integer, AnswerKeyDTO> target = switch (outcome.phase()) {
        case PRIMARY -> keys;
        case VERIFY -> verificationKeys;
        case TIE_BREAK -> verificationKeys;
        case SUBJECTIVE_REVIEW -> keys;
      };
      target.putAll(outcome.keyDelta());
      warnings.addAll(outcome.warnings());
    }

    ensureAllAnswersPresent(sources, keys, warnings);
    subjectiveReview(paperId, structure, sources, keys, warnings, heartbeat);

    Map<Integer, AnswerKeyDTO> tieBreakKeys = new LinkedHashMap<>();
    List<QuestionSource> conflicts = findConflicts(sources, keys, verificationKeys);
    if (!conflicts.isEmpty()) {
      Map<Boolean, List<QuestionSource>> conflictSplit = conflicts.stream()
          .collect(Collectors.partitioningBy(this::needsVision));
      List<AnswerBatchRunner.BatchTask> tieBreakTasks = new ArrayList<>();
      int tieBreakBatchIndex = 0;
      tieBreakBatchIndex = addTextTasks(tieBreakTasks, paperId,
          conflictSplit.getOrDefault(false, List.of()), AnswerPhase.TIE_BREAK,
          tieBreakBatchIndex);
      addVisualTasks(tieBreakTasks, paperId,
          conflictSplit.getOrDefault(true, List.of()), AnswerPhase.TIE_BREAK,
          tieBreakBatchIndex);
      List<AnswerBatchRunner.BatchOutcome> tieBreakOutcomes =
          answerBatchRunner.run(tieBreakTasks, AnswerBatchRunner.CancelPolicy.IGNORE_FATAL,
              heartbeat);
      for (AnswerBatchRunner.BatchOutcome outcome : tieBreakOutcomes) {
        if (outcome.fatal() != null) {
          warnings.add("答案键裁决批次失败，已保留主答案：" + safeMessage(outcome.fatal()));
          continue;
        }
        tieBreakKeys.putAll(outcome.keyDelta());
        warnings.addAll(outcome.warnings());
      }
    }
    applyConsistency(sources, keys, verificationKeys, tieBreakKeys, warnings);
    persistAnswerKeysIncrementally(paperId, structure, keys, warnings);
  }

  private void persistPrimaryDelta(Long paperId, PaperStructureService.BuiltStructure structure,
                                   Map<Integer, AnswerKeyDTO> keys, List<String> warnings,
                                   AnswerBatchRunner.BatchOutcome outcome) {
    if (outcome.fatal() != null || outcome.keyDelta().isEmpty()) {
      return;
    }
    if (outcome.phase() == AnswerPhase.PRIMARY) {
      keys.putAll(outcome.keyDelta());
      persistAnswerKeysIncrementally(paperId, structure, keys, warnings);
    }
  }

  private void persistAnswerKeysIncrementally(
      Long paperId,
      PaperStructureService.BuiltStructure structure,
      Map<Integer, AnswerKeyDTO> keys,
      List<String> warnings
  ) {
    if (keys.isEmpty()) {
      return;
    }
    try {
      structureService.applyAndStoreAnswerKeys(
          paperId, structure, new LinkedHashMap<>(keys));
    } catch (RuntimeException e) {
      // 增量落库失败不阻断本轮，结束时还会统一再存一次。
      warnings.add("答案键增量保存失败，将在本轮结束时重试：" + safeMessage(e));
      PaperFlowLog.warn("ANSWER_KEY_INCREMENTAL_STORE_FAILED paperId={}, error={}",
          paperId, safeMessage(e));
    }
  }

  private int addTextTasks(List<AnswerBatchRunner.BatchTask> tasks, Long paperId,
                           List<QuestionSource> questions, AnswerPhase phase, int nextIndex) {
    if (questions.isEmpty()) {
      return nextIndex;
    }
    List<List<QuestionSource>> batches = phase == AnswerPhase.TIE_BREAK
        ? questions.stream().map(List::of).toList()
        : textAnswerBatches(questions);
    PaperFlowLog.event("ANSWER_KEY_TEXT_BATCHES paperId={}, phase={}, questions={}, batches={}",
        paperId, phase, questions.size(), batches.size());
    for (List<QuestionSource> batch : batches) {
      int batchIndex = nextIndex++;
      tasks.add(new AnswerBatchRunner.BatchTask(phase, batchIndex,
          () -> executeTextBatch(paperId, batch, phase, batchIndex)));
    }
    return nextIndex;
  }

  private AnswerBatchRunner.BatchOutcome executeTextBatch(
      Long paperId, List<QuestionSource> batch, AnswerPhase phase, int batchIndex) {
    List<Integer> questionIndexes = batch.stream()
        .map(source -> source.question().questionIndex()).toList();
    PaperFlowLog.event("ANSWER_KEY_TEXT_BATCH_BEGIN paperId={}, phase={}, questionIndexes={}",
        paperId, phase, questionIndexes);
    analysisPacer.awaitAvailable(paperId);
    try {
      AnswerBatchDTO result = requestTextAnswers(paperId, batch, phase);
      log.info("纯文字题{}: paperId={}, questions={}", phaseLabel(phase), paperId,
          batch.stream().map(source -> source.question().questionIndex() + 1).toList());
      Map<Integer, AnswerKeyDTO> delta = new LinkedHashMap<>();
      List<String> batchWarnings = new ArrayList<>();
      mergeGenerated(batch, result, AnswerKeyDTO.SOURCE_TEXT, delta, batchWarnings);
      return AnswerBatchRunner.BatchOutcome.success(
          phase, batchIndex, delta, batchWarnings);
    } catch (Exception e) {
      PaperFlowLog.error("ANSWER_KEY_TEXT_BATCH_FAILED paperId={}, phase={}, "
              + "questionIndexes={}",
          e, paperId, phase, questionIndexes);
      log.warn("纯文字题答案键生成失败: paperId={}, error={}", paperId,
          e.getMessage(), e);
      String failureLabel = phase == AnswerPhase.VERIFY
          ? "纯文字题独立复核失败，已保留主求解答案："
          : "纯文字题" + phaseLabel(phase) + "失败，已保留其他批次结果：";
      return AnswerBatchRunner.BatchOutcome.failure(phase, batchIndex,
          failureLabel + safeMessage(e));
    }
  }

  private AnswerBatchDTO requestTextAnswers(
      Long paperId,
      List<QuestionSource> batch,
      AnswerPhase phase
  ) {
    AnswerBatchDTO result = null;
    Exception lastError = null;
    for (int attempt = 1; attempt <= ANSWER_KEY_ATTEMPTS; attempt++) {
      int currentAttempt = attempt;
      try {
        if (attempt > 1) {
          backoffForRateLimit(attempt - 1);
        }
        analysisPacer.awaitAvailable(paperId);
        String questions = renderQuestions(batch);
        questions += phaseInstruction(phase);
        if (attempt > 1) {
          questions += missingAnswerInstruction(batch, result);
        }
        String userPrompt = textUserPrompt.render(Map.of("questions", questions));
        ChatClient client = phase == AnswerPhase.TIE_BREAK
            ? providerRegistry.getPlainReasoningChatClient()
            : providerRegistry.getPlainChatClient();
        result = structuredOutputInvoker.invokeWithoutSchemaValidation(
            client,
            textSystemPrompt.render() + "\n\n" + answerOutputConverter.getFormat(),
            userPrompt,
            answerOutputConverter,
            ErrorCode.PAPER_EXTRACTION_FAILED,
            "纯文字题答案键生成失败：",
            "试卷答案键-纯文字-" + paperId,
            log,
            content -> PaperFlowLog.raw(
                "ANSWER_KEY_TEXT_MODEL_RESPONSE paperId=" + paperId
                    + " phase=" + phase + " attempt=" + currentAttempt, content)
        );
        PaperFlowLog.value("ANSWER_KEY_TEXT_PARSED paperId=" + paperId
            + " phase=" + phase + " attempt=" + attempt, result);
        if (hasCompleteAnswers(batch, result)) {
          PaperFlowLog.event("ANSWER_KEY_TEXT_ATTEMPT status=SUCCESS paperId={}, phase={}, "
                  + "attempt={}, answers={}",
              paperId, phase, attempt, result.answers() == null ? 0 : result.answers().size());
          return result;
        }
        PaperFlowLog.warn("ANSWER_KEY_TEXT_ATTEMPT status=INCOMPLETE paperId={}, phase={}, "
                + "attempt={}, result={}",
            paperId, phase, attempt, result);
      } catch (Exception e) {
        lastError = e;
        PaperFlowLog.error("ANSWER_KEY_TEXT_ATTEMPT status=FAILED paperId={}, phase={}, "
                + "attempt={}",
            e, paperId, phase, attempt);
      }
    }
    throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
        "模型连续 " + ANSWER_KEY_ATTEMPTS + " 次未返回完整候选答案", lastError);
  }

  private String phaseLabel(AnswerPhase phase) {
    return switch (phase) {
      case PRIMARY -> "主求解";
      case VERIFY -> "独立复核";
      case TIE_BREAK -> "冲突裁决";
      case SUBJECTIVE_REVIEW -> "评分依据复核";
    };
  }

  private List<List<QuestionSource>> textAnswerBatches(List<QuestionSource> questions) {
    List<List<QuestionSource>> batches = new ArrayList<>();
    for (int start = 0; start < questions.size(); start += TEXT_BATCH_SIZE) {
      batches.add(questions.subList(
          start, Math.min(questions.size(), start + TEXT_BATCH_SIZE)));
    }
    return batches;
  }

  private String phaseInstruction(AnswerPhase phase) {
    return switch (phase) {
      case PRIMARY -> "";
      case VERIFY ->
          "\n这是独立复核。请从题目、原文和图片重新计算，不要假设任何已有答案一定正确。";
      case TIE_BREAK ->
          "\n主求解与独立复核存在分歧。请忽略两边的答案，重新完整计算，只输出本次得到的最终候选答案。";
      case SUBJECTIVE_REVIEW -> "";
    };
  }

  private int addVisualTasks(List<AnswerBatchRunner.BatchTask> tasks, Long paperId,
                             List<QuestionSource> questions, AnswerPhase phase, int nextIndex) {
    if (questions.isEmpty()) {
      return nextIndex;
    }
    Map<Integer, List<QuestionSource>> byPage = questions.stream()
        .collect(Collectors.groupingBy(
            source -> Optional.ofNullable(source.question().sourcePageFrom()).orElse(0),
            LinkedHashMap::new,
            Collectors.toList()
        ));
    for (Map.Entry<Integer, List<QuestionSource>> entry : byPage.entrySet()) {
      for (List<QuestionSource> batch : packVisualBatches(paperId, entry.getValue())) {
        int batchIndex = nextIndex++;
        int pageNo = entry.getKey();
        tasks.add(new AnswerBatchRunner.BatchTask(phase, batchIndex,
            () -> executeVisualBatch(paperId, pageNo, batch, phase, batchIndex)));
      }
    }
    return nextIndex;
  }

  private List<List<QuestionSource>> packVisualBatches(Long paperId, List<QuestionSource> questions) {
    List<List<QuestionSource>> batches = new ArrayList<>();
    List<QuestionSource> current = new ArrayList<>();
    int currentImages = 0;
    for (QuestionSource source : questions) {
      int imageCount = modelImages(paperId, source).size();
      if (!current.isEmpty()
          && (current.size() >= MAX_QUESTIONS_PER_CALL
              || currentImages + imageCount > MAX_IMAGES_PER_CALL)) {
        batches.add(List.copyOf(current));
        current.clear();
        currentImages = 0;
      }
      current.add(source);
      currentImages += imageCount;
    }
    if (!current.isEmpty()) {
      batches.add(List.copyOf(current));
    }
    return batches;
  }

  private AnswerBatchRunner.BatchOutcome executeVisualBatch(
      Long paperId,
      int pageNo,
      List<QuestionSource> batch,
      AnswerPhase phase,
      int batchIndex
  ) {
    long startNanos = System.nanoTime();
    List<Integer> questionIndexes = batch.stream()
        .map(source -> source.question().questionIndex() + 1)
        .toList();
    int imageCount = 0;
    PaperFlowLog.event("ANSWER_KEY_VISUAL_BATCH_BEGIN paperId={}, phase={}, pageNo={}, "
            + "questionIndexes={}",
        paperId, phase, pageNo, questionIndexes);
    analysisPacer.awaitAvailable(paperId);
    try {
      List<Media> media = new ArrayList<>();
      StringBuilder imageGuide = new StringBuilder();
      for (QuestionSource source : batch) {
        imageGuide.append("\n[questionIndex=").append(source.question().questionIndex())
            .append("]\n题干：").append(source.question().question()).append('\n');
        appendContext(imageGuide, source.context());
        appendOptions(imageGuide, source.question().options());
        List<ModelImage> images = modelImages(paperId, source);
        for (ModelImage image : images) {
          media.add(loadMedia(paperId, image));
          imageCount++;
          imageGuide.append("图片顺序 ").append(media.size())
              .append("：").append(image.role()).append("（只对应 questionIndex=")
              .append(source.question().questionIndex()).append("）\n");
        }
      }

      log.info("图形题{}: paperId={}, page={}, questions={}, images={}, provider={}, model={}",
          phaseLabel(phase), paperId, pageNo, questionIndexes, imageCount,
          providerRegistry.getCurrentUserVisionProviderId(),
          providerRegistry.getCurrentUserVisionModelName());
      AnswerBatchDTO result = null;
      for (int attempt = 1; attempt <= ANSWER_KEY_ATTEMPTS; attempt++) {
        int currentAttempt = attempt;
        String retryInstruction = attempt == 1 ? ""
            : missingAnswerInstruction(batch, result);
        String verificationInstruction = phaseInstruction(phase);
        String userPrompt = visualUserPrompt.render(
            Map.of("questions", imageGuide + verificationInstruction + retryInstruction));
        try {
          result = structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
              providerRegistry.getDefaultVisionChatClient(),
              visualSystemPrompt.render() + "\n\n" + answerOutputConverter.getFormat(),
              userPrompt,
              media,
              answerOutputConverter,
              ErrorCode.PAPER_EXTRACTION_FAILED,
              "图形题答案键生成失败：",
              "试卷答案键-第" + pageNo + "页",
              log,
              content -> PaperFlowLog.raw(
                  "ANSWER_KEY_VISUAL_MODEL_RESPONSE paperId=" + paperId
                      + " phase=" + phase + " pageNo=" + pageNo + " attempt=" + currentAttempt,
                  content)
          );
          PaperFlowLog.value("ANSWER_KEY_VISUAL_PARSED paperId=" + paperId
              + " phase=" + phase + " pageNo=" + pageNo + " attempt=" + attempt, result);
        } catch (VisionRequestRejectedException e) {
          PaperFlowLog.error("ANSWER_KEY_VISUAL_ATTEMPT status=REJECTED paperId={}, phase={}, "
                  + "pageNo={}, attempt={}",
              e, paperId, phase, pageNo, attempt);
          throw e;
        } catch (BusinessException e) {
          VisionFailureClass failureClass = VisionFailureClassifier.classify(e);
          if (!VisionRetryPolicy.shouldRetry(
              failureClass, attempt, ANSWER_KEY_ATTEMPTS)) {
            PaperFlowLog.error("ANSWER_KEY_VISUAL_ATTEMPT status=FAILED paperId={}, phase={}, "
                    + "pageNo={}, attempt={}, failureClass={}",
                e, paperId, phase, pageNo, attempt, failureClass);
            throw e;
          }
          if (failureClass == VisionFailureClass.RATE_LIMITED) {
            backoffForRateLimit(attempt);
          }
          log.warn("图形题答案键调用失败，准备重试: paperId={}, page={}, failureClass={}, "
                  + "attempt={}/{}",
              paperId, pageNo, failureClass, attempt, ANSWER_KEY_ATTEMPTS);
          continue;
        }
        if (hasCompleteAnswers(batch, result)) {
          PaperFlowLog.event("ANSWER_KEY_VISUAL_ATTEMPT status=SUCCESS paperId={}, phase={}, "
                  + "pageNo={}, attempt={}, answers={}",
              paperId, phase, pageNo, attempt,
              result.answers() == null ? 0 : result.answers().size());
          break;
        }
        PaperFlowLog.warn("ANSWER_KEY_VISUAL_ATTEMPT status=INCOMPLETE paperId={}, phase={}, "
                + "pageNo={}, attempt={}, result={}",
            paperId, phase, pageNo, attempt, result);
      }
      if (!hasCompleteAnswers(batch, result)) {
        throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
            "第 " + pageNo + " 页图形题连续 " + ANSWER_KEY_ATTEMPTS
                + " 次未返回完整候选答案");
      }
      Map<Integer, AnswerKeyDTO> delta = new LinkedHashMap<>();
      List<String> batchWarnings = new ArrayList<>();
      mergeGenerated(batch, result, AnswerKeyDTO.SOURCE_VISION, delta, batchWarnings);
      PaperFlowLog.event("ANSWER_KEY_VISUAL_BATCH_END status=SUCCESS paperId={}, phase={}, "
              + "pageNo={}, questionIndexes={}, images={}",
          paperId, phase, pageNo, questionIndexes, imageCount);
      return AnswerBatchRunner.BatchOutcome.success(
          phase, batchIndex, delta, batchWarnings);
    } catch (VisionRequestRejectedException e) {
      PaperFlowLog.error("ANSWER_KEY_VISUAL_BATCH_REJECTED paperId={}, phase={}, pageNo={}, "
              + "questionIndexes={}, images={}",
          e, paperId, phase, pageNo, questionIndexes, imageCount);
      return AnswerBatchRunner.BatchOutcome.failure(phase, batchIndex,
          "第 " + pageNo + " 页图片题调用被限流，已保留其他批次结果");
    } catch (Exception e) {
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      PaperFlowLog.error("ANSWER_KEY_VISUAL_BATCH_FAILED paperId={}, phase={}, pageNo={}, "
              + "questionIndexes={}, images={}, durationMs={}",
          e, paperId, phase, pageNo, questionIndexes, imageCount, elapsedMs);
      log.warn("含图题答案键生成失败: paperId={}, page={}, questions={}, images={}, elapsedMs={}, error={}",
          paperId, pageNo, questionIndexes, imageCount, elapsedMs,
          e.getMessage(), e);
      return AnswerBatchRunner.BatchOutcome.failure(phase, batchIndex,
          "第 " + pageNo + " 页图片题" + phaseLabel(phase) + "失败，已保留其他批次结果："
              + safeMessage(e));
    }
  }

  private void backoffForRateLimit(int attempt) {
    try {
      VisionRetryPolicy.rateLimitBackoff(attempt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.PAPER_EXTRACTION_FAILED, "图形题答案键生成等待被中断，请稍后重试", e);
    }
  }

  private void mergeGenerated(List<QuestionSource> batch, AnswerBatchDTO result, String source,
                              Map<Integer, AnswerKeyDTO> keys, List<String> warnings) {
    Map<Integer, GeneratedAnswerDTO> generated = selectAnswersForBatch(batch, result);
    for (QuestionSource questionSource : batch) {
      InterviewQuestionDTO question = questionSource.question();
      GeneratedAnswerDTO item = generated.get(question.questionIndex());
      if (!hasUsableGeneratedAnswer(question, item)) {
        throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
            "第 " + (question.questionIndex() + 1) + " 题模型未返回候选答案");
      }
      keys.put(question.questionIndex(), toAnswerKey(question, item, source));
    }
  }

  private boolean hasCompleteAnswers(List<QuestionSource> batch, AnswerBatchDTO result) {
    Map<Integer, GeneratedAnswerDTO> generated = selectAnswersForBatch(batch, result);
    return batch.stream().allMatch(source ->
        hasUsableGeneratedAnswer(source.question(),
            generated.get(source.question().questionIndex())));
  }

  private String missingAnswerInstruction(
      List<QuestionSource> batch,
      AnswerBatchDTO previous
  ) {
    Map<Integer, GeneratedAnswerDTO> generated = selectAnswersForBatch(batch, previous);
    List<Integer> missing = batch.stream()
        .filter(source -> !hasUsableGeneratedAnswer(
            source.question(), generated.get(source.question().questionIndex())))
        .map(source -> source.question().questionIndex())
        .toList();
    return "\n上一次返回缺少以下题目的候选答案：questionIndex=" + missing
        + "。请重新输出本批全部题目，并且：\n"
        + "1. 每道题 referenceAnswer 必须是非空答案，禁止空字符串、null、暂缺或“无法确定”；\n"
        + "2. 选择题必须同时返回 selectedOptions 和与其一致的 referenceAnswer；\n"
        + "3. 填空题必须返回可直接判分的答案；\n"
        + "4. 作图题必须用文字写明补画、连接或涂色的具体格子/位置，不能只返回图形摘要；\n"
        + "5. 置信度可以低于 60，但答案本身不得缺失。";
  }

  private void subjectiveReview(Long paperId, PaperStructureService.BuiltStructure structure,
                                List<QuestionSource> sources,
                                Map<Integer, AnswerKeyDTO> keys, List<String> warnings,
                                Runnable heartbeat) {
    List<QuestionSource> candidates = sources.stream()
        .filter(source -> isSubjective(source.question()))
        .filter(source -> {
          AnswerKeyDTO key = keys.get(source.question().questionIndex());
          String mode = resolveAnswerMode(source.question(),
              key == null ? source.question().referenceAnswer() : key.referenceAnswer(),
              key == null ? source.question().keyPoints() : key.keyPoints(),
              key == null ? source.question().scoringRubric() : key.scoringRubric());
          return AnswerKeyDTO.ANSWER_MODE_SAMPLE.equals(mode)
              || AnswerKeyDTO.ANSWER_MODE_RUBRIC_ONLY.equals(mode);
        })
        .toList();
    if (candidates.isEmpty()) {
      return;
    }
    PaperFlowLog.event("ANSWER_SUBJECTIVE_REVIEW_BEGIN paperId={}, questions={}",
        paperId, candidates.size());
    for (List<QuestionSource> batch : textAnswerBatches(candidates)) {
      executeSubjectiveReviewBatch(paperId, batch, keys, warnings);
      persistAnswerKeysIncrementally(paperId, structure, keys, warnings);
      if (heartbeat != null) {
        heartbeat.run();
      }
    }
  }

  private void executeSubjectiveReviewBatch(Long paperId, List<QuestionSource> batch,
                                            Map<Integer, AnswerKeyDTO> keys,
                                            List<String> warnings) {
    List<Integer> questionIndexes = batch.stream()
        .map(source -> source.question().questionIndex()).toList();
    analysisPacer.awaitAvailable(paperId);
    try {
      String questions = renderQuestions(batch);
      String userPrompt = subjectiveReviewUserPrompt.render(Map.of("questions", questions));
      SubjectiveReviewBatchDTO result = structuredOutputInvoker.invokeWithoutSchemaValidation(
          providerRegistry.getPlainChatClient(),
          subjectiveReviewSystemPrompt.render() + "\n\n"
              + subjectiveReviewConverter.getFormat(),
          userPrompt,
          subjectiveReviewConverter,
          ErrorCode.PAPER_EXTRACTION_FAILED,
          "主观题评分依据补全失败：",
          "试卷答案键-主观题复核-" + paperId,
          log);
      PaperFlowLog.value("ANSWER_SUBJECTIVE_REVIEW_PARSED paperId=" + paperId
          + " questionIndexes=" + questionIndexes, result);
      if (result == null || result.reviews() == null) {
        return;
      }
      for (SubjectiveReviewDTO review : result.reviews()) {
        if (review == null || !questionIndexes.contains(review.questionIndex())) {
          continue;
        }
        AnswerKeyDTO base = keys.get(review.questionIndex());
        if (base == null) {
          continue;
        }
      QuestionSource source = batch.stream()
          .filter(item -> item.question().questionIndex() == review.questionIndex())
          .findFirst()
          .orElse(null);
      if (source == null) {
        continue;
      }
        List<String> keyPoints = review.keyPoints() == null
            ? List.of() : review.keyPoints();
        boolean hasBasis = !keyPoints.isEmpty()
            || (review.scoringRubric() != null && !review.scoringRubric().isBlank())
            || !AnswerKeySupport.isMissing(review.sampleAnswer());
        if (!hasBasis) {
          continue;
        }
        String reference = AnswerKeySupport.isMissing(review.sampleAnswer())
            ? base.referenceAnswer() : review.sampleAnswer().trim();
        String answerMode = resolveAnswerMode(
            source.question(), reference, keyPoints, review.scoringRubric());
        keys.put(review.questionIndex(), new AnswerKeyDTO(
            base.questionIndex(),
            reference,
            base.optionCorrectness(),
            base.analysis(),
            keyPoints,
            review.scoringRubric(),
            base.figureSummary(),
            base.requiresVisualGrading(),
            base.confidence(),
            base.source(),
            base.candidates(),
            base.resolutionStatus(),
            base.consistencyScore(),
            answerMode,
            base.fingerprint()));
      }
      PaperFlowLog.event("ANSWER_SUBJECTIVE_REVIEW_END paperId={}, questionIndexes={}",
          paperId, questionIndexes);
    } catch (Exception e) {
      PaperFlowLog.error("ANSWER_SUBJECTIVE_REVIEW_FAILED paperId={}, questionIndexes={}",
          e, paperId, questionIndexes);
      warnings.add("主观题评分依据补全失败，已保留原答案：" + safeMessage(e));
    }
  }

  private boolean isSubjective(InterviewQuestionDTO question) {
    if (question == null) {
      return false;
    }
    String type = question.questionType();
    return type == null
        || InterviewQuestionDTO.TYPE_SHORT_ANSWER.equals(type)
        || InterviewQuestionDTO.TYPE_READING.equals(type)
        || InterviewQuestionDTO.TYPE_WRITING.equals(type)
        || InterviewQuestionDTO.TYPE_CASE_ANALYSIS.equals(type)
        || InterviewQuestionDTO.TYPE_OTHER.equals(type);
  }

  private void ensureAllAnswersPresent(
      List<QuestionSource> sources,
      Map<Integer, AnswerKeyDTO> keys,
      List<String> warnings
  ) {
    for (QuestionSource source : sources) {
      InterviewQuestionDTO question = source.question();
      int index = question.questionIndex();
      AnswerKeyDTO key = keys.get(index);
      String answerMode = resolveAnswerMode(question,
          key == null ? question.referenceAnswer() : key.referenceAnswer(),
          key == null ? question.keyPoints() : key.keyPoints(),
          key == null ? question.scoringRubric() : key.scoringRubric());
      if (AnswerKeyDTO.ANSWER_MODE_MANUAL_REVIEW.equals(answerMode)) {
        keys.put(index, manualReviewKey(question, key));
        warnings.add("第 " + (index + 1) + " 题缺少评分依据，已转人工复核");
      } else if (AnswerKeyDTO.ANSWER_MODE_FIXED.equals(answerMode)
          && (key == null || isMissingAnswer(key.referenceAnswer()))) {
        keys.put(index, manualReviewKey(question, key));
        warnings.add("第 " + (index + 1) + " 题未生成确定答案，已转人工复核");
      } else if (key == null) {
        keys.put(index, scoringBasisKey(question, answerMode));
      }
    }
  }

  private AnswerKeyDTO scoringBasisKey(InterviewQuestionDTO question, String answerMode) {
    return new AnswerKeyDTO(
        question.questionIndex(),
        isMissingAnswer(question.referenceAnswer()) ? "暂缺" : question.referenceAnswer(),
        question.isChoiceQuestion()
            ? correctnessOf(question, question.referenceAnswer()) : Map.of(),
        question.analysis(),
        question.keyPoints() == null ? List.of() : question.keyPoints(),
        question.scoringRubric(),
        question.visualContext(),
        question.requiresVisualGrading(),
        0,
        AnswerKeyDTO.SOURCE_STRUCTURE,
        List.of(),
        null,
        null,
        answerMode,
        QuestionFingerprint.of(question));
  }

  private AnswerKeyDTO manualReviewKey(InterviewQuestionDTO question, AnswerKeyDTO key) {
    if (key == null) {
      return new AnswerKeyDTO(
          question.questionIndex(),
          "暂缺",
          Map.of(),
          question.analysis(),
          question.keyPoints() == null ? List.of() : question.keyPoints(),
          question.scoringRubric(),
          question.visualContext(),
          question.requiresVisualGrading(),
          0,
          AnswerKeyDTO.SOURCE_TEXT,
          List.of(),
          null,
          null,
          AnswerKeyDTO.ANSWER_MODE_MANUAL_REVIEW,
          QuestionFingerprint.of(question));
    }
    return new AnswerKeyDTO(
        key.questionIndex(),
        key.referenceAnswer(),
        key.optionCorrectness(),
        key.analysis(),
        key.keyPoints(),
        key.scoringRubric(),
        key.figureSummary(),
        key.requiresVisualGrading(),
        key.confidence(),
        key.source(),
        key.candidates(),
        key.resolutionStatus(),
        key.consistencyScore(),
        AnswerKeyDTO.ANSWER_MODE_MANUAL_REVIEW,
        key.fingerprint());
  }

  private AnswerKeyDTO existingAnswerKey(InterviewQuestionDTO question) {
    String reference = question.referenceAnswer();
    Map<String, Boolean> correctness = question.isChoiceQuestion()
        ? correctnessOf(question, reference) : Map.of();
    return new AnswerKeyDTO(
        question.questionIndex(),
        reference,
        correctness,
        question.analysis(),
        question.keyPoints() == null ? List.of() : question.keyPoints(),
        question.scoringRubric(),
        question.visualContext(),
        question.requiresVisualGrading(),
        question.answerKeyConfidence() == null ? 100 : question.answerKeyConfidence(),
        question.answerKeySource(),
        List.of(),
        null,
        null,
        resolveAnswerMode(question, reference, question.keyPoints(), question.scoringRubric()),
        QuestionFingerprint.of(question));
  }

  static String resolveAnswerMode(InterviewQuestionDTO question, String referenceAnswer,
                                  List<String> keyPoints, String scoringRubric) {
    String type = question == null ? null : question.questionType();
    if (InterviewQuestionDTO.TYPE_SINGLE_CHOICE.equals(type)
        || InterviewQuestionDTO.TYPE_MULTI_CHOICE.equals(type)
        || InterviewQuestionDTO.TYPE_TRUE_FALSE.equals(type)
        || InterviewQuestionDTO.TYPE_FILL_BLANK.equals(type)) {
      return AnswerKeyDTO.ANSWER_MODE_FIXED;
    }
    boolean hasAnswer = !AnswerKeySupport.isMissing(referenceAnswer);
    boolean hasRubric = scoringRubric != null && !scoringRubric.isBlank();
    boolean hasPoints = keyPoints != null && !keyPoints.isEmpty();
    if (InterviewQuestionDTO.TYPE_WRITING.equals(type)) {
      if (hasRubric || hasPoints) {
        return AnswerKeyDTO.ANSWER_MODE_RUBRIC_ONLY;
      }
      return hasAnswer
          ? AnswerKeyDTO.ANSWER_MODE_SAMPLE : AnswerKeyDTO.ANSWER_MODE_MANUAL_REVIEW;
    }
    if (hasAnswer) {
      return AnswerKeyDTO.ANSWER_MODE_SAMPLE;
    }
    if (hasRubric || hasPoints) {
      return AnswerKeyDTO.ANSWER_MODE_RUBRIC_ONLY;
    }
    return AnswerKeyDTO.ANSWER_MODE_MANUAL_REVIEW;
  }

  private String safeMessage(Throwable error) {
    if (error == null) {
      return "未知错误";
    }
    String message = error.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }

  private List<QuestionSource> findConflicts(
      List<QuestionSource> sources,
      Map<Integer, AnswerKeyDTO> primaryKeys,
      Map<Integer, AnswerKeyDTO> verificationKeys
  ) {
    return sources.stream()
        .filter(source -> requiresConsistencyVote(source.question()))
        .filter(source -> {
          AnswerKeyDTO primary = primaryKeys.get(source.question().questionIndex());
          AnswerKeyDTO verifier = verificationKeys.get(source.question().questionIndex());
          return primary != null && verifier != null
              && hasResolvableCandidate(source.question(), primary, verifier, null)
              && consistencyResolver.resolve(source.question(), primary, verifier, null).status()
                  .equals(AnswerConsistencyResolver.STATUS_CONFLICT);
        })
        .toList();
  }

  private void applyConsistency(
      List<QuestionSource> sources,
      Map<Integer, AnswerKeyDTO> keys,
      Map<Integer, AnswerKeyDTO> verificationKeys,
      Map<Integer, AnswerKeyDTO> tieBreakKeys,
      List<String> warnings
  ) {
    for (QuestionSource source : sources) {
      if (!requiresConsistencyVote(source.question())) {
        continue;
      }
      int index = source.question().questionIndex();
      AnswerKeyDTO primary = keys.get(index);
      AnswerKeyDTO verifier = verificationKeys.get(index);
      AnswerKeyDTO tieBreaker = tieBreakKeys.get(index);
      if (!hasResolvableCandidate(source.question(), primary, verifier, tieBreaker)) {
        continue;
      }
      AnswerConsistencyResolver.Resolution resolution =
          consistencyResolver.resolve(source.question(), primary, verifier, tieBreaker);
      keys.put(index, resolution.answerKey());
      if (AnswerConsistencyResolver.STATUS_MAJORITY.equals(resolution.status())) {
        warnings.add("第 " + (index + 1) + " 题答案存在分歧，已按多数裁决");
      } else if (AnswerConsistencyResolver.STATUS_CONFLICT.equals(resolution.status())) {
        warnings.add("第 " + (index + 1) + " 题答案仍存在冲突，已保留最佳候选并标记低置信度");
      } else if (AnswerConsistencyResolver.STATUS_SINGLE.equals(resolution.status())
          && verifier == null) {
        warnings.add("第 " + (index + 1) + " 题缺少独立复核，已保留主答案");
      }
    }
  }

  private boolean requiresConsistencyVote(InterviewQuestionDTO question) {
    return question != null && question.isChoiceQuestion();
  }

  private boolean hasResolvableCandidate(
      InterviewQuestionDTO question,
      AnswerKeyDTO... candidates
  ) {
    for (AnswerKeyDTO candidate : candidates) {
      if (candidate == null || isMissingAnswer(candidate.referenceAnswer())) {
        continue;
      }
      if (normalizeChoiceAnswer(question, candidate.referenceAnswer()) != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * 第二轮只生成解析、考点和评分规则。此阶段以第一轮已确认答案为事实，
   * 即使解析生成失败也不会丢失或改写答案。
   */
  private void enrichAnalyses(Long paperId, List<QuestionSource> sources,
                              Map<Integer, AnswerKeyDTO> keys, List<String> warnings,
                              Runnable heartbeat) {
    List<AnalysisInput> inputs = sources.stream()
        .map(source -> new AnalysisInput(
            source, keys.get(source.question().questionIndex())))
        .filter(input -> input.base() != null)
        .toList();
    Map<Integer, AnswerKeyDTO> snapshot = Map.copyOf(keys);
    boolean freeTextModel = !inputs.isEmpty()
        && freeModelConcurrencyGate.isCurrentTextModelFree(providerRegistry);
    int effectiveBatchSize = effectiveAnalysisBatchSize(freeTextModel);
    List<List<AnalysisInput>> batches = analysisBatches(inputs, effectiveBatchSize);
    PaperFlowLog.event("ANSWER_ANALYSIS_BATCHES paperId={}, questions={}, batches={}, "
            + "batchSize={}",
        paperId, inputs.size(), batches.size(), effectiveBatchSize);
    List<AnalysisBatchResult> results = analysisBatchScheduler.runBatches(
        batches,
        batch -> () -> executeAnalysisBatch(paperId, batch, snapshot, freeTextModel),
        (batch, error) -> failedAnalysisBatch(batch, error),
        heartbeat);
    mergeAnalysisResults(results, keys, warnings);

    List<AnalysisInput> missing = inputs.stream()
        .filter(input -> isMissingAnalysis(
            keys.get(input.source().question().questionIndex())))
        .toList();
    if (!missing.isEmpty()) {
      recoverMissingAnalyses(paperId, missing, keys, warnings, heartbeat, freeTextModel);
    }
  }

  private void mergeAnalysisResults(
      List<AnalysisBatchResult> results,
      Map<Integer, AnswerKeyDTO> keys,
      List<String> warnings
  ) {
    for (AnalysisBatchResult result : results) {
      for (AnalysisResult item : result.results()) {
        if (item.key() != null) {
          keys.put(item.questionIndex(), item.key());
        }
      }
      if (hasText(result.warning())) {
        warnings.add(result.warning());
      }
    }
  }

  private void recoverMissingAnalyses(
      Long paperId,
      List<AnalysisInput> missing,
      Map<Integer, AnswerKeyDTO> keys,
      List<String> warnings,
      Runnable heartbeat,
      boolean freeTextModel
  ) {
    int maxCalls = Math.max(0, answerProperties.getAnalysisRecoveryMaxCalls());
    List<AnalysisInput> targets = missing.stream().limit(maxCalls).toList();
    int skipped = missing.size() - targets.size();
    long deadline = System.currentTimeMillis()
        + answerProperties.getAnalysisRecoveryMaxWait().toMillis();
    PaperFlowLog.event("ANSWER_ANALYSIS_RECOVERY_PLAN paperId={}, missing={}, targets={}, "
            + "skipped={}, deadlineMs={}",
        paperId, missing.size(), targets.size(), skipped, deadline);
    if (targets.isEmpty()) {
      if (skipped > 0) {
        warnings.add(analysisRecoverySkippedWarning(skipped));
      }
      return;
    }

    PaperFlowLog.event("ANSWER_ANALYSIS_RECOVERY_BEGIN paperId={}, targets={}",
        paperId, targets.size());
    Map<Integer, AnswerKeyDTO> snapshot = Map.copyOf(keys);
    List<List<AnalysisInput>> recoveryBatches = analysisBatches(targets, 1);
    List<AnalysisBatchResult> recoveryResults = analysisBatchScheduler.runRecovery(
        recoveryBatches,
        batch -> () -> executeAnalysisBatch(paperId, batch, snapshot, freeTextModel),
        (batch, error) -> failedAnalysisBatch(batch, error),
        heartbeat,
        deadline);
    mergeAnalysisResults(recoveryResults, keys, warnings);
    if (skipped > 0) {
      warnings.add(analysisRecoverySkippedWarning(skipped));
    }
    long remaining = countMissing(keys, targets);
    PaperFlowLog.event("ANSWER_ANALYSIS_RECOVERY_END paperId={}, targets={}, "
            + "remainingMissing={}",
        paperId, targets.size(), remaining);
  }

  private boolean isMissingAnalysis(AnswerKeyDTO key) {
    return key == null
        || !hasText(key.analysis())
        || key.analysis().contains("解析未生成");
  }

  private long countMissing(Map<Integer, AnswerKeyDTO> keys, List<AnalysisInput> inputs) {
    return inputs.stream()
        .filter(input -> isMissingAnalysis(
            keys.get(input.source().question().questionIndex())))
        .count();
  }

  private String analysisRecoverySkippedWarning(int skipped) {
    return "解析补跑预算用尽，另有 " + skipped + " 道题未生成解析，可手动重新生成";
  }

  private AnalysisBatchResult executeAnalysisBatch(
      Long paperId,
      List<AnalysisInput> batch,
      Map<Integer, AnswerKeyDTO> snapshot,
      boolean freeTextModel
  ) {
    List<Integer> questionIndexes = batch.stream()
        .map(input -> input.source().question().questionIndex())
        .toList();
    PaperFlowLog.event("ANSWER_ANALYSIS_BATCH_BEGIN paperId={}, questionIndexes={}",
        paperId, questionIndexes);
    try {
      AnalysisBatchDTO result = requestAnalysisBatch(
          paperId, batch, snapshot, freeTextModel);
      PaperFlowLog.value("ANSWER_ANALYSIS_PARSED paperId=" + paperId
          + " questionIndexes=" + questionIndexes, result);
      Map<Integer, GeneratedAnalysisDTO> generated =
          selectAnalysesForBatch(batch.stream().map(AnalysisInput::source).toList(), result);
      List<AnalysisResult> outcomes = new ArrayList<>(batch.size());
      List<Integer> missing = new ArrayList<>();
      for (AnalysisInput input : batch) {
        int questionIndex = input.source().question().questionIndex();
        GeneratedAnalysisDTO item = generated.get(questionIndex);
        if (input.base() != null && item != null && hasText(item.analysis())) {
          outcomes.add(new AnalysisResult(
              questionIndex, withAnalysis(input.base(), item)));
        } else {
          outcomes.add(new AnalysisResult(
              questionIndex,
              input.base() == null ? null : withMissingAnalysis(input.base())));
          missing.add(questionIndex);
        }
      }
      String warning = missing.isEmpty() ? null : analysisMissingWarning(missing);
      if (!missing.isEmpty()) {
        PaperFlowLog.event("ANSWER_ANALYSIS_BATCH_MISSING paperId={}, questionIndexes={}",
            paperId, missing);
      }
      PaperFlowLog.event("ANSWER_ANALYSIS_BATCH_END status=SUCCESS paperId={}, "
              + "questionIndexes={}, generated={}, missing={}",
          paperId, questionIndexes, generated.size(), missing);
      return new AnalysisBatchResult(outcomes, warning);
    } catch (Exception e) {
      if (RetryableFailureClassifier.isRateLimitFailure(e)) {
        PaperFlowLog.event("ANSWER_ANALYSIS_RATE_LIMIT_THROTTLE paperId={}, "
                + "questionIndexes={}",
            paperId, questionIndexes);
      }
      PaperFlowLog.error("ANSWER_ANALYSIS_BATCH_FAILED paperId={}, questionIndexes={}",
          e, paperId, questionIndexes);
      log.warn("题目解析生成失败: paperId={}, questionIndexes={}, error={}",
          paperId, questionIndexes, e.getMessage(), e);
      return failedAnalysisBatch(batch, e);
    }
  }

  private AnalysisBatchDTO requestAnalysisBatch(
      Long paperId,
      List<AnalysisInput> batch,
      Map<Integer, AnswerKeyDTO> snapshot,
      boolean freeTextModel
  ) {
    List<QuestionSource> sources = batch.stream()
        .map(AnalysisInput::source)
        .toList();
    List<Integer> questionIndexes = sources.stream()
        .map(source -> source.question().questionIndex())
        .toList();
    String userPrompt = analysisUserPrompt.render(Map.of(
        "questions", renderAnalysisQuestions(sources, snapshot)));
    analysisPacer.awaitAvailable(paperId, freeTextModel);
    return structuredOutputInvoker.invokeWithoutSchemaValidation(
        providerRegistry.getPlainChatClient(),
        analysisSystemPrompt.render() + "\n\n" + analysisOutputConverter.getFormat(),
        userPrompt,
        analysisOutputConverter,
        ErrorCode.PAPER_EXTRACTION_FAILED,
        "题目解析生成失败：",
        "试卷答案解析-" + paperId,
        log,
        content -> PaperFlowLog.raw(
            "ANSWER_ANALYSIS_MODEL_RESPONSE paperId=" + paperId
                + " questionIndexes=" + questionIndexes, content)
    );
  }

  private AnalysisBatchResult failedAnalysisBatch(
      List<AnalysisInput> batch,
      Throwable error
  ) {
    List<Integer> questionIndexes = batch.stream()
        .map(input -> input.source().question().questionIndex())
        .toList();
    PaperFlowLog.error("ANSWER_ANALYSIS_QUESTION_FAILED questionIndexes={}",
        error, questionIndexes);
    log.warn("题目解析批次失败，已标记为缺失: questionIndexes={}, error={}",
        questionIndexes, error.getMessage());
    List<AnalysisResult> results = batch.stream()
        .map(input -> new AnalysisResult(
            input.source().question().questionIndex(),
            input.base() == null ? null : withMissingAnalysis(input.base())))
        .toList();
    return new AnalysisBatchResult(results, analysisMissingWarning(questionIndexes));
  }

  private AnswerKeyDTO withMissingAnalysis(AnswerKeyDTO base) {
    String existing = StudentFacingFeedbackSanitizer.sanitize(base.analysis());
    String analysis = hasText(existing) ? existing : ANALYSIS_MISSING_MESSAGE;
    return new AnswerKeyDTO(
        base.questionIndex(),
        base.referenceAnswer(),
        base.optionCorrectness(),
        limitText(analysis, MAX_ANALYSIS_CHARS),
        base.keyPoints(),
        base.scoringRubric(),
        base.figureSummary(),
        base.requiresVisualGrading(),
        base.confidence(),
        base.source(),
        base.candidates(),
        base.resolutionStatus(),
        base.consistencyScore(),
        base.answerMode(),
        base.fingerprint()
    );
  }

  private String analysisMissingWarning(List<Integer> questionIndexes) {
    String labels = questionIndexes.stream()
        .map(index -> String.valueOf(index + 1))
        .collect(Collectors.joining("、"));
    return "第 " + labels + " 题解析未生成，已保留标准答案";
  }

  int effectiveAnalysisBatchSize(boolean freeTextModel) {
    return freeTextModel
        ? freeModelConcurrencyGate.getAnalysisBatchSize()
        : answerProperties.getAnalysisBatchSize();
  }

  static <T> List<List<T>> analysisBatches(List<T> values, int batchSize) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    int effectiveBatchSize = Math.max(1, batchSize);
    List<List<T>> batches = new ArrayList<>();
    for (int start = 0; start < values.size(); start += effectiveBatchSize) {
      batches.add(List.copyOf(
          values.subList(start,
              Math.min(values.size(), start + effectiveBatchSize))));
    }
    return batches;
  }

  private String renderAnalysisQuestions(List<QuestionSource> batch,
                                         Map<Integer, AnswerKeyDTO> keys) {
    StringBuilder sb = new StringBuilder();
    for (QuestionSource source : batch) {
      AnswerKeyDTO key = keys.get(source.question().questionIndex());
      sb.append("\n[questionIndex=").append(source.question().questionIndex())
          .append("]\n题干：").append(source.question().question()).append('\n');
      appendContext(sb, source.context());
      appendOptions(sb, source.question().options());
      sb.append("已确认答案：")
          .append(key == null ? "暂缺" : key.referenceAnswer()).append('\n');
      if (key != null && hasText(key.figureSummary())) {
        sb.append("图形摘要：").append(key.figureSummary()).append('\n');
      }
    }
    return sb.toString();
  }

  private Map<Integer, GeneratedAnalysisDTO> selectAnalysesForBatch(
      List<QuestionSource> batch, AnalysisBatchDTO result) {
    List<Integer> expected = batch.stream()
        .map(source -> source.question().questionIndex())
        .toList();
    return selectAnalyses(expected, result);
  }

  static Map<Integer, GeneratedAnalysisDTO> selectAnalyses(
      List<Integer> expectedIndexes, AnalysisBatchDTO result) {
    if (expectedIndexes == null || expectedIndexes.isEmpty()
        || result == null || result.analyses() == null) {
      return Map.of();
    }
    List<GeneratedAnalysisDTO> items = result.analyses().stream()
        .filter(item -> item != null)
        .toList();
    Map<Integer, GeneratedAnalysisDTO> selected = new LinkedHashMap<>();
    for (GeneratedAnalysisDTO item : items) {
      if (expectedIndexes.contains(item.questionIndex())) {
        selected.putIfAbsent(item.questionIndex(), item);
      }
    }
    if (selected.isEmpty() && expectedIndexes.size() == 1 && items.size() == 1) {
      GeneratedAnalysisDTO item = items.get(0);
      selected.put(expectedIndexes.get(0), new GeneratedAnalysisDTO(
          expectedIndexes.get(0), item.analysis(), item.keyPoints(), item.scoringRubric()));
    }
    return selected;
  }

  private AnswerKeyDTO withAnalysis(AnswerKeyDTO base, GeneratedAnalysisDTO generated) {
    List<String> keyPoints = generated.keyPoints() == null || generated.keyPoints().isEmpty()
        ? safeList(base.keyPoints()) : generated.keyPoints();
    keyPoints = keyPoints.stream()
        .filter(this::hasText)
        .limit(4)
        .map(point -> limitText(point, 50))
        .toList();
    return new AnswerKeyDTO(
        base.questionIndex(),
        base.referenceAnswer(),
        base.optionCorrectness(),
        limitText(StudentFacingFeedbackSanitizer.sanitize(
            firstNonBlank(generated.analysis(), base.analysis(),
                ANALYSIS_MISSING_MESSAGE)), MAX_ANALYSIS_CHARS),
        keyPoints,
        limitText(firstNonBlank(generated.scoringRubric(), base.scoringRubric(), null), 160),
        base.figureSummary(),
        base.requiresVisualGrading(),
        base.confidence(),
        base.source(),
        base.candidates(),
        base.resolutionStatus(),
        base.consistencyScore(),
        base.answerMode(),
        base.fingerprint()
    );
  }

  static Map<Integer, GeneratedAnswerDTO> selectAnswersForBatch(
      List<QuestionSource> batch,
      AnswerBatchDTO result
  ) {
    List<Integer> expected = batch.stream()
        .map(source -> source.question().questionIndex())
        .toList();
    return selectAnswers(expected, result);
  }

  static Map<Integer, GeneratedAnswerDTO> selectAnswers(
      List<Integer> expectedIndexes,
      AnswerBatchDTO result
  ) {
    if (expectedIndexes == null || expectedIndexes.isEmpty()
        || result == null || result.answers() == null) {
      return Map.of();
    }
    List<GeneratedAnswerDTO> items = result.answers().stream()
        .filter(item -> item != null)
        .toList();
    Map<Integer, GeneratedAnswerDTO> selected = new LinkedHashMap<>();
    for (GeneratedAnswerDTO item : items) {
      if (expectedIndexes.contains(item.questionIndex())) {
        selected.putIfAbsent(item.questionIndex(), item);
      }
    }
    // 整批一致的 1-based 偏移是可证明的映射，允许按题号 -1 对齐，但仍记日志。
    if (selected.isEmpty() && !items.isEmpty()
        && items.size() == expectedIndexes.size()
        && isConsistentOneBasedShift(items, expectedIndexes)) {
      for (GeneratedAnswerDTO item : items) {
        int expected = item.questionIndex() - 1;
        selected.put(expected, new GeneratedAnswerDTO(
            expected,
            item.referenceAnswer(),
            item.selectedOptions(),
            item.figureSummary(),
            item.requiresVisualGrading(),
            item.confidence(),
            item.derivedResult()));
      }
      PaperFlowLog.warn("ANSWER_KEY_ONE_BASED_SHIFT expected={}, returned={}",
          expectedIndexes,
          items.stream().map(GeneratedAnswerDTO::questionIndex).toList());
    }
    // 题号对不上时不再静默改挂：交给上层带期望题号重试，
    // 避免把一道题的答案错绑到另一道题。
    if (selected.isEmpty() && !items.isEmpty()) {
      PaperFlowLog.warn("ANSWER_KEY_QUESTION_INDEX_MISMATCH expected={}, returned={}",
          expectedIndexes,
          items.stream().map(GeneratedAnswerDTO::questionIndex).toList());
    }
    return selected;
  }

  private static boolean isConsistentOneBasedShift(
      List<GeneratedAnswerDTO> items, List<Integer> expectedIndexes) {
    Set<Integer> returned = items.stream()
        .map(GeneratedAnswerDTO::questionIndex)
        .collect(Collectors.toSet());
    Set<Integer> shifted = expectedIndexes.stream()
        .map(index -> index + 1)
        .collect(Collectors.toSet());
    return returned.equals(shifted);
  }

  private AnswerKeyDTO toAnswerKey(InterviewQuestionDTO question, GeneratedAnswerDTO generated,
                                   String source) {
    String referenceAnswer = candidateAnswer(question, generated);
    if (InterviewQuestionDTO.TYPE_FILL_BLANK.equals(question.questionType())) {
      referenceAnswer = normalizeOrdinalAnswer(referenceAnswer);
    }
    int confidence = clamp(generated.confidence());
    Map<String, Boolean> correctness = question.isChoiceQuestion()
        ? correctnessOf(question, referenceAnswer) : Map.of();

    boolean requiresVisual = requiresVisualGrading(question,
        Boolean.TRUE.equals(generated.requiresVisualGrading()));
    String finalAnswer = referenceAnswer == null ? "暂缺" : referenceAnswer;
    return new AnswerKeyDTO(
        question.questionIndex(),
        finalAnswer,
        correctness,
        limitText(StudentFacingFeedbackSanitizer.sanitize(question.analysis()),
            MAX_ANALYSIS_CHARS),
        safeList(question.keyPoints()),
        question.scoringRubric(),
        limitText(firstNonBlank(generated.figureSummary(), question.visualContext(), null),
            MAX_FIGURE_SUMMARY_CHARS),
        requiresVisual,
        confidence,
        source,
        List.of(),
        null,
        null,
        resolveAnswerMode(question, finalAnswer, question.keyPoints(), question.scoringRubric()),
        QuestionFingerprint.of(question)
    );
  }

  private boolean needsVision(QuestionSource source) {
    InterviewQuestionDTO question = source.question();
    boolean hasOptionImage = question.options() != null
        && question.options().stream().anyMatch(option -> hasText(option.assetId()));
    boolean hasVisualHint = VISUAL_HINT_PATTERN.matcher(
        question.question() == null ? "" : question.question()).find();
    return hasOptionImage || !source.inheritedAssetIds().isEmpty() || hasVisualHint;
  }

  private boolean isUsableAnswerKey(InterviewQuestionDTO question) {
    if (question == null || AnswerKeySupport.isMissing(question.referenceAnswer())) {
      return false;
    }
    String source = question.answerKeySource();
    if (source == null) {
      return false;
    }
    if (AnswerKeyDTO.SOURCE_MANUAL.equals(source)
        || AnswerKeyDTO.SOURCE_OFFICIAL.equals(source)
        || AnswerKeyDTO.SOURCE_STRUCTURE.equals(source)) {
      return true;
    }
    return question.answerKeyConfidence() != null && question.answerKeyConfidence() > 0;
  }

  private boolean hasMissingAnalysis(List<QuestionSource> sources,
                                     Map<Integer, AnswerKeyDTO> keys) {
    for (QuestionSource source : sources) {
      AnswerKeyDTO key = keys.get(source.question().questionIndex());
      if (key == null) {
        continue;
      }
      String analysis = key.analysis();
      if (!hasText(analysis) || analysis.contains("解析未生成")) {
        return true;
      }
    }
    return false;
  }

  static boolean hasUsableGeneratedAnswer(InterviewQuestionDTO question,
                                          GeneratedAnswerDTO generated) {
    return candidateAnswer(question, generated) != null;
  }

  static String candidateAnswer(InterviewQuestionDTO question,
                                GeneratedAnswerDTO generated) {
    if (generated == null) {
      return null;
    }
    if (question.isChoiceQuestion()) {
      String derived = optionFromDerivedResult(question, generated.derivedResult());
      if (derived != null) {
        return derived;
      }
      String answer = selectedOptionsAnswer(question, generated.selectedOptions());
      if (!isMissingAnswer(answer)) {
        return answer;
      }
      String reference = normalizeChoiceAnswer(question, generated.referenceAnswer());
      return !isMissingAnswer(reference) ? reference : null;
    }
    String reference = normalizeChoiceAnswer(generated.referenceAnswer());
    if (!isMissingAnswer(reference)) {
      return reference;
    }
    return null;
  }

  private static boolean isMissingAnswer(String value) {
    return AnswerKeySupport.isMissing(value);
  }

  private static String optionFromDerivedResult(
      InterviewQuestionDTO question,
      String derivedResult
  ) {
    if (question.options() == null || derivedResult == null || derivedResult.isBlank()) {
      return null;
    }
    String derived = normalizeOptionResult(derivedResult);
    if (derived.isBlank()) {
      return null;
    }
    List<String> matches = question.options().stream()
        .filter(option -> option != null && option.key() != null && option.text() != null)
        .filter(option -> {
          String optionText = normalizeOptionResult(option.text());
          return !optionText.isBlank()
              && (optionText.equals(derived)
                  || optionText.contains(derived) && derived.length() >= 2
                  || derived.contains(optionText) && optionText.length() >= 2);
        })
        .map(option -> option.key().toUpperCase())
        .distinct()
        .toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private static String normalizeOptionResult(String value) {
    if (value == null) {
      return "";
    }
    return value.trim()
        .replace('（', '(')
        .replace('）', ')')
        .replace('，', ',')
        .replace('∞', '∞')
        .replaceAll("\\s+", "")
        .replaceAll("[。.;；]$", "");
  }

  private List<ModelImage> modelImages(Long paperId, QuestionSource source) {
    List<ModelImage> images = new ArrayList<>();
    boolean hasOptionTiles = source.question().options() != null
        && source.question().options().stream().anyMatch(option -> hasText(option.assetId()));
    List<QuestionOption> imageOptions = source.question().options() == null
        ? List.of()
        : source.question().options().stream()
            .filter(option -> hasText(option.assetId()))
            .sorted(Comparator.comparing(QuestionOption::key))
            .toList();
    List<String> optionRows = source.inheritedAssetIds().stream()
        .filter(assetId -> isAssetKind(paperId, assetId, "OPTION_ROW"))
        .toList();

    source.inheritedAssetIds().stream()
        .filter(assetId -> !hasOptionTiles || !isOptionAsset(paperId, assetId))
        .forEach(assetId -> images.add(new ModelImage(
            assetId, source.question().sourcePageFrom(),
            "Q" + (source.question().questionIndex() + 1) + "_CONTEXT")));
    if (imageOptions.size() >= 2) {
      // 独立选项图更容易让模型逐项比较；整行图只作为无法切分时的兜底。
      imageOptions.forEach(option -> images.add(new ModelImage(
          option.assetId(), source.question().sourcePageFrom(),
          "Q" + (source.question().questionIndex() + 1) + "_OPTION_" + option.key())));
    } else if (!optionRows.isEmpty()) {
      optionRows.forEach(assetId -> images.add(new ModelImage(
          assetId, source.question().sourcePageFrom(),
          "Q" + (source.question().questionIndex() + 1) + "_OPTION_ROW_A_TO_D")));
    }
    Integer pageNo = source.question().sourcePageFrom();
    if (pageNo != null && images.size() < MAX_IMAGES_PER_CALL) {
      // 裁图用于聚焦，整页图作为完整性兜底；任何裁图异常时模型仍能看到原题上下文。
      images.add(new ModelImage(null, pageNo,
          "Q" + (source.question().questionIndex() + 1) + "_FULL_PAGE_CONTEXT_" + pageNo));
    }
    if (images.size() <= MAX_IMAGES_PER_CALL) {
      return images;
    }
    return pageNo == null ? images.subList(0, MAX_IMAGES_PER_CALL)
        : List.of(new ModelImage(null, pageNo,
            "Q" + (source.question().questionIndex() + 1) + "_PAGE_" + pageNo));
  }

  private Media loadMedia(Long paperId, ModelImage image) {
    if (image.assetId() != null) {
      PaperAssetEntity asset = assetService.find(paperId, image.assetId())
          .orElseThrow(() -> new BusinessException(
              ErrorCode.NOT_FOUND, "题目图片不存在"));
      byte[] bytes = assetService.download(asset);
      try {
        bytes = imageNormalizer.prepareForVision(bytes);
      } catch (Exception e) {
        PaperFlowLog.error("ANSWER_KEY_MEDIA_PREPARE_FAILED paperId={}, assetId={}",
            e, paperId, image.assetId());
        log.warn("题目图片预处理失败，使用原图: paperId={}, asset={}, error={}",
            paperId, image.assetId(), e.getMessage(), e);
      }
      return new Media(MimeTypeUtils.IMAGE_JPEG,
          new ByteArrayResource(bytes, image.role() + ".jpg"));
    }
    PaperPageEntity page = pageRepository.findByPaperIdAndPageNo(paperId, image.pageNo())
        .orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, "试卷页不存在"));
    if (page.getImageKey() == null) {
      throw new BusinessException(
          ErrorCode.NOT_FOUND, "试卷页没有原图");
    }
    return new Media(MimeTypeUtils.IMAGE_JPEG,
        new ByteArrayResource(storageService.downloadFile(page.getImageKey()),
            image.role() + ".jpg"));
  }

  private boolean isOptionAsset(Long paperId, String assetId) {
    return assetService.find(paperId, assetId)
        .map(asset -> asset.getKind() != null && asset.getKind().startsWith("OPTION_"))
        .orElse(false);
  }

  private boolean isAssetKind(Long paperId, String assetId, String kind) {
    return assetService.find(paperId, assetId)
        .map(asset -> kind.equals(asset.getKind()))
        .orElse(false);
  }

  private String renderQuestions(List<QuestionSource> questions) {
    StringBuilder sb = new StringBuilder();
    for (QuestionSource source : questions) {
      sb.append("\n[questionIndex=").append(source.question().questionIndex())
          .append("]\n题干：").append(source.question().question()).append('\n');
      appendContext(sb, source.context());
      appendOptions(sb, source.question().options());
    }
    return sb.toString();
  }

  private void appendContext(StringBuilder sb, String context) {
    if (hasText(context)) {
      sb.append("阅读上下文：\n").append(context.trim()).append('\n');
    }
  }

  private void appendOptions(StringBuilder sb, List<QuestionOption> options) {
    if (options == null) {
      return;
    }
    for (QuestionOption option : options) {
      String text = hasText(option.text()) ? option.text() : "见图像选项";
      sb.append(option.key()).append(". ").append(text).append('\n');
    }
  }

  private List<QuestionSource> collectQuestionSources(PaperTree tree) {
    List<QuestionSource> sources = new ArrayList<>();
    for (PaperNode node : tree.questionNodes()) {
      if (node.question() == null || node.questionIndex() == null) {
        continue;
      }
      List<String> assetIds = new ArrayList<>();
      if (node.question().options() != null) {
        node.question().options().stream()
            .map(QuestionOption::assetId)
            .filter(this::hasText)
            .forEach(assetIds::add);
      }
      if (node.meta() != null && node.meta().assetIds() != null) {
        assetIds.addAll(node.meta().assetIds());
      }
      for (PaperNode material : tree.materialsOf(node.questionIndex())) {
        if (material.meta() != null && material.meta().assetIds() != null) {
          assetIds.addAll(material.meta().assetIds());
        }
      }
      sources.add(new QuestionSource(
          node.question(),
          distinct(assetIds),
          tree.contextOf(node.questionIndex())
      ));
    }
    return sources;
  }

  private boolean requiresVisualGrading(InterviewQuestionDTO question, boolean modelValue) {
    if (question.isChoiceQuestion()
        || InterviewQuestionDTO.TYPE_FILL_BLANK.equals(question.questionType())
        && isPlainAnswer(question.referenceAnswer())) {
      return false;
    }
    if (DRAWING_HINT_PATTERN.matcher(
        question.question() == null ? "" : question.question()).find()) {
      return true;
    }
    return modelValue;
  }

  private boolean isPlainAnswer(String value) {
    return value != null && value.trim().matches("[0-9a-zA-Z/\\-+.=]+");
  }

  private List<String> distinct(List<String> values) {
    Set<String> distinct = new LinkedHashSet<>();
    for (String value : values) {
      if (hasText(value)) {
        distinct.add(value);
      }
    }
    return List.copyOf(distinct);
  }

  private List<String> safeList(List<String> values) {
    return values == null ? List.of() : values.stream()
        .filter(this::hasText)
        .toList();
  }

  static String selectedOptionsAnswer(List<String> selectedOptions) {
    Set<String> keys = new LinkedHashSet<>();
    if (selectedOptions != null) {
      for (String selected : selectedOptions) {
        if (!hasTextStatic(selected)) {
          continue;
        }
        String normalized = selected.trim().toUpperCase();
        if (normalized.matches("[A-H]{1,8}")) {
          normalized.chars()
              .mapToObj(value -> String.valueOf((char) value))
              .forEach(keys::add);
        }
      }
    }
    if (!keys.isEmpty()) {
      return String.join("", new java.util.TreeSet<>(keys));
    }
    return null;
  }

  static String selectedOptionsAnswer(
      InterviewQuestionDTO question,
      List<String> selectedOptions
  ) {
    List<String> optionKeys = optionKeys(question);
    if (optionKeys.isEmpty()) {
      return selectedOptionsAnswer(selectedOptions);
    }
    Set<String> selected = new LinkedHashSet<>();
    if (selectedOptions != null) {
      for (String value : selectedOptions) {
        selected.addAll(parseChoiceKeys(optionKeys, value));
      }
    }
    if (selected.isEmpty()) {
      return null;
    }
    return optionKeys.stream()
        .filter(selected::contains)
        .collect(Collectors.joining());
  }

  static Map<String, Boolean> correctnessOf(String referenceAnswer) {
    Map<String, Boolean> result = new LinkedHashMap<>();
    String normalized = referenceAnswer == null ? "" : referenceAnswer;
    for (char key = 'A'; key <= 'H'; key++) {
      result.put(String.valueOf(key), normalized.indexOf(key) >= 0);
    }
    return result;
  }

  static Map<String, Boolean> correctnessOf(
      InterviewQuestionDTO question,
      String referenceAnswer
  ) {
    List<String> optionKeys = optionKeys(question);
    if (optionKeys.isEmpty()) {
      return correctnessOf(referenceAnswer);
    }
    return correctnessOf(optionKeys, referenceAnswer);
  }

  static Map<String, Boolean> correctnessOf(
      List<String> rawOptionKeys,
      String referenceAnswer
  ) {
    List<String> optionKeys = canonicalOptionKeys(rawOptionKeys);
    if (optionKeys.isEmpty()) {
      return correctnessOf(referenceAnswer);
    }
    Set<String> selected = new LinkedHashSet<>(
        parseChoiceKeys(optionKeys, referenceAnswer));
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (String key : optionKeys) {
      result.put(key, selected.contains(key));
    }
    return result;
  }

  static String normalizeChoiceAnswer(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.matches("[A-Ha-h\\s,，、]+")) {
      String normalized = trimmed.toUpperCase().replaceAll("[^A-H]", "");
      return normalized.isBlank() ? null : normalized;
    }
    return trimmed.isBlank() ? null : trimmed;
  }

  static String normalizeChoiceAnswer(InterviewQuestionDTO question, String value) {
    if (question == null) {
      return normalizeChoiceAnswer(value);
    }
    return normalizeChoiceAnswer(optionKeys(question), value);
  }

  static String normalizeChoiceAnswer(List<String> rawOptionKeys, String value) {
    List<String> optionKeys = canonicalOptionKeys(rawOptionKeys);
    if (optionKeys.isEmpty()) {
      return normalizeChoiceAnswer(value);
    }
    if (isMissingAnswer(value)) {
      return normalizeChoiceAnswer(value);
    }
    List<String> selected = parseChoiceKeys(optionKeys, value);
    return selected.isEmpty() ? null : String.join("", selected);
  }

  private static List<String> optionKeys(InterviewQuestionDTO question) {
    if (question == null || question.options() == null) {
      return List.of();
    }
    Set<String> keys = new LinkedHashSet<>();
    for (QuestionOption option : question.options()) {
      String key = option == null ? null : canonicalChoiceKey(option.key());
      if (key != null) {
        keys.add(key);
      }
    }
    return List.copyOf(keys);
  }

  private static List<String> canonicalOptionKeys(List<String> rawOptionKeys) {
    if (rawOptionKeys == null || rawOptionKeys.isEmpty()) {
      return List.of();
    }
    Set<String> keys = new LinkedHashSet<>();
    for (String rawKey : rawOptionKeys) {
      String key = canonicalChoiceKey(rawKey);
      if (key != null) {
        keys.add(key);
      }
    }
    return List.copyOf(keys);
  }

  private static List<String> parseChoiceKeys(List<String> optionKeys, String value) {
    if (optionKeys == null || optionKeys.isEmpty() || value == null) {
      return List.of();
    }
    String text = normalizeChoiceText(value);
    if (text.isBlank()) {
      return List.of();
    }
    Map<String, String> aliases = choiceAliases(optionKeys);
    List<String> aliasOrder = aliases.keySet().stream()
        .sorted(Comparator.comparingInt(String::length).reversed()
            .thenComparing(Comparator.naturalOrder()))
        .toList();
    Set<String> selected = new LinkedHashSet<>();
    int cursor = 0;
    while (cursor < text.length()) {
      String matchedAlias = null;
      for (String alias : aliasOrder) {
        if (text.startsWith(alias, cursor)) {
          matchedAlias = alias;
          break;
        }
      }
      if (matchedAlias == null) {
        return List.of();
      }
      selected.add(aliases.get(matchedAlias));
      cursor += matchedAlias.length();
    }
    return optionKeys.stream()
        .filter(selected::contains)
        .toList();
  }

  private static Map<String, String> choiceAliases(List<String> optionKeys) {
    Map<String, String> aliases = new LinkedHashMap<>();
    for (String key : optionKeys) {
      aliases.putIfAbsent(key, key);
    }
    for (int i = 0; i < optionKeys.size(); i++) {
      String key = optionKeys.get(i);
      if (i < 26) {
        aliases.putIfAbsent(String.valueOf((char) ('A' + i)), key);
      }
      aliases.putIfAbsent(String.valueOf(i + 1), key);
      if (i < CIRCLED_NUMBERS.length()) {
        aliases.putIfAbsent(String.valueOf(CIRCLED_NUMBERS.charAt(i)), key);
      }
    }
    return aliases;
  }

  private static String normalizeChoiceText(String value) {
    if (value == null) {
      return "";
    }
    String text = value.trim().toUpperCase(Locale.ROOT);
    text = CHOICE_ANSWER_PREFIX.matcher(text).replaceFirst("");
    text = CHOICE_WRAPPER.matcher(text).replaceAll("");
    text = CHOICE_SEPARATOR.matcher(text).replaceAll("");
    text = text.replaceAll("[。．.,，、;；:：]+$", "");
    return text.replaceAll("(?:选项|项)$", "");
  }

  private static String canonicalChoiceKey(String value) {
    if (value == null) {
      return null;
    }
    String key = value.trim().toUpperCase(Locale.ROOT);
    key = key.replaceAll("^[\\(（\\[【]+", "");
    key = key.replaceAll("[\\)）\\]】。．.,，、;；:：]+$", "");
    return key.isBlank() ? null : key;
  }

  private static boolean hasTextStatic(String value) {
    return value != null && !value.isBlank();
  }

  static String normalizeOrdinalAnswer(String value) {
    if (value == null) {
      return null;
    }
    return value
        .replace('①', '1')
        .replace('②', '2')
        .replace('③', '3')
        .replace('④', '4')
        .replace('⑤', '5')
        .replace('⑥', '6')
        .replace('⑦', '7')
        .replace('⑧', '8')
        .replace('⑨', '9');
  }

  static AnswerKeyDTO concise(AnswerKeyDTO key) {
    if (key == null) {
      return null;
    }
    List<String> keyPoints = key.keyPoints() == null
        ? List.of()
        : key.keyPoints().stream()
            .filter(point -> point != null && !point.isBlank())
            .map(point -> limitText(point, 50))
            .toList();
    return new AnswerKeyDTO(
        key.questionIndex(),
        key.referenceAnswer(),
        key.optionCorrectness(),
        limitText(StudentFacingFeedbackSanitizer.sanitize(key.analysis()),
            MAX_ANALYSIS_CHARS),
        keyPoints,
        limitText(key.scoringRubric(), 160),
        limitText(key.figureSummary(), MAX_FIGURE_SUMMARY_CHARS),
        key.requiresVisualGrading(),
        key.confidence(),
        key.source(),
        key.candidates(),
        key.resolutionStatus(),
        key.consistencyScore(),
        key.answerMode(),
        key.fingerprint()
    );
  }

  private int clamp(Integer confidence) {
    return confidence == null ? 0 : Math.max(0, Math.min(100, confidence));
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  static String limitText(String value, int maxChars) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.replaceAll("\\s+", " ").trim();
    if (normalized.length() <= maxChars) {
      return normalized;
    }
    int sentenceEnd = -1;
    for (int i = Math.min(maxChars, normalized.length()) - 1; i >= maxChars / 2; i--) {
      char ch = normalized.charAt(i);
      if (ch == '。' || ch == '！' || ch == '？' || ch == '；') {
        sentenceEnd = i + 1;
        break;
      }
    }
    if (sentenceEnd > 0) {
      return normalized.substring(0, sentenceEnd).stripTrailing();
    }
    return normalized.substring(0, maxChars).stripTrailing() + "…";
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private String read(ResourceLoader loader, String path) throws IOException {
    return loader.getResource(path).getContentAsString(StandardCharsets.UTF_8);
  }
}
