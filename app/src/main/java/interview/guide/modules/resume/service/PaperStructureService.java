package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.AnswerKeySupport;
import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.ExecutorRejectedException;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.QuestionFingerprint;
import interview.guide.modules.resume.model.PageRecognition;
import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PageAssetGeometry;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;
import interview.guide.modules.resume.service.BlockTreeDTO.BlockOptionDTO;
import interview.guide.modules.resume.log.PaperFlowLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 试卷结构化：模型只标块类型与行号，后端按行切片建树。
 */
@Slf4j
@Service
public class PaperStructureService {

  private static final Set<String> SUBJECTS = Set.of("chinese", "math", "english");
  private static final int SOFT_CHUNK_CHARS = 2800;
  private static final int HARD_CHUNK_CHARS = 5000;
  private static final int MAX_EMPTY_SUBMIT_ROUNDS = 50;
  private static final long BACKPRESSURE_SLEEP_MS = 200L;
  private static final Pattern SCORE_PATTERN =
      Pattern.compile("[（(]?\\s*(\\d+(?:\\.\\d+)?)\\s*分\\s*[）)]?");

  private final ResumeRepository resumeRepository;
  private final PaperPageRepository pageRepository;
  private final PaperAssetService assetService;
  private final PaperSubQuestionGrouper subQuestionGrouper;
  private final PaperAssetOwnershipResolver ownershipResolver;
  private final PaperAssetBindingService assetBindingService;
  private final PaperQuestionGroupAssembler questionGroupAssembler;
  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final TransactionalExecutor transactionalExecutor;
  private final ObjectMapper objectMapper;
  private final PaperAnalysisPacer analysisPacer;
  private final BoundedExecutor structureExecutor;
  private final PromptTemplate systemPromptTemplate;
  private final PromptTemplate userPromptTemplate;
  private final BeanOutputConverter<BlockTreeDTO> outputConverter;

  public PaperStructureService(
      ResumeRepository resumeRepository,
      PaperPageRepository pageRepository,
      PaperAssetService assetService,
      PaperSubQuestionGrouper subQuestionGrouper,
      PaperAssetOwnershipResolver ownershipResolver,
      PaperAssetBindingService assetBindingService,
      PaperQuestionGroupAssembler questionGroupAssembler,
      LlmProviderRegistry llmProviderRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      TransactionalExecutor transactionalExecutor,
      ObjectMapper objectMapper,
      ResourceLoader resourceLoader,
      PaperAnalysisPacer analysisPacer,
      @Qualifier("paperStructureExecutor") BoundedExecutor structureExecutor
  ) {
    this.resumeRepository = resumeRepository;
    this.pageRepository = pageRepository;
    this.assetService = assetService;
    this.subQuestionGrouper = subQuestionGrouper;
    this.ownershipResolver = ownershipResolver;
    this.assetBindingService = assetBindingService;
    this.questionGroupAssembler = questionGroupAssembler;
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.transactionalExecutor = transactionalExecutor;
    this.objectMapper = objectMapper;
    this.analysisPacer = analysisPacer;
    this.structureExecutor = structureExecutor;
    this.systemPromptTemplate = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-structure-system.st"));
    this.userPromptTemplate = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-structure-user.st"));
    this.outputConverter = new BeanOutputConverter<>(BlockTreeDTO.class);
  }

  public record BuiltStructure(
      String subjectId,
      PaperTree paperTree,
      List<InterviewQuestionDTO> questions,
      int totalScore,
      List<String> warnings
  ) {
    public BuiltStructure withAnswerKeys(Map<Integer, AnswerKeyDTO> answerKeys) {
      if (answerKeys == null || answerKeys.isEmpty()) {
        return this;
      }
      List<InterviewQuestionDTO> applied = questions.stream()
          .map(question -> {
            AnswerKeyDTO key = answerKeys.get(question.questionIndex());
            return key == null ? question
                : question.withAnswerKey(AnswerKeyPreparationService.concise(key));
          })
          .toList();
      int score = applied.stream()
          .mapToInt(question -> question.fullScore() == null ? 0 : question.fullScore())
          .sum();
      return new BuiltStructure(subjectId, paperTree.withQuestions(applied), applied, score, warnings);
    }

    public BuiltStructure withAdditionalWarnings(List<String> additionalWarnings) {
      if (additionalWarnings == null || additionalWarnings.isEmpty()) {
        return this;
      }
      List<String> merged = new ArrayList<>(warnings);
      merged.addAll(additionalWarnings);
      return new BuiltStructure(subjectId, paperTree, questions, totalScore, List.copyOf(merged));
    }
  }

  /**
   * 结构预览：有缓存且文本未变时直接复用。
   */
  public BuiltStructure build(Long paperId) {
    long startNanos = System.nanoTime();
    try (PaperFlowLog.Scope ignored =
             PaperFlowLog.open("PAPER_STRUCTURE", paperId, null, null)) {
      return buildInScope(paperId, startNanos, true);
    } catch (RuntimeException e) {
      PaperFlowLog.error("PAPER_STRUCTURE_END status=FAILED, durationMs={}",
          e, elapsedMs(startNanos));
      throw e;
    }
  }

  /**
   * 只读构建：只接受已持久化的结构快照，缓存缺失时直接失败，不触发模型调用。
   */
  public BuiltStructure buildCached(Long paperId) {
    long startNanos = System.nanoTime();
    try (PaperFlowLog.Scope ignored =
             PaperFlowLog.open("PAPER_STRUCTURE_READ_ONLY", paperId, null, null)) {
      return buildInScope(paperId, startNanos, false);
    } catch (RuntimeException e) {
      PaperFlowLog.error("PAPER_STRUCTURE_READ_ONLY_END status=FAILED, durationMs={}",
          e, elapsedMs(startNanos));
      throw e;
    }
  }

  private BuiltStructure buildInScope(Long paperId, long startNanos, boolean allowModelCall) {
    PracticePaperEntity paper = resumeRepository.findById(paperId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_NOT_FOUND, "试卷不存在"));
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId).stream()
        .filter(page -> page.getStatus() == PageStatus.READY)
        .toList();
    if (pages.isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_PARSE_FAILED, "没有可切题的页");
    }

    List<PaperAssetEntity> allAssets = assetService.list(paperId);
    PaperFlowLog.event("PAPER_STRUCTURE_BEGIN paperName={}, pages={}, assets={}, "
            + "structureHash={}, hasCachedStructure={}",
        paper.getOriginalFilename(), pages.size(), allAssets.size(),
        paper.getStructureHash(), paper.getStructureJson() != null);
    Map<Integer, List<PaperAssetEntity>> assetsByPage = new HashMap<>();
    for (PaperAssetEntity asset : allAssets) {
      if (asset.getPageNo() != null) {
        assetsByPage.computeIfAbsent(asset.getPageNo(), key -> new ArrayList<>()).add(asset);
      }
    }

    List<String> lines = new ArrayList<>();
    List<int[]> pageRanges = new ArrayList<>();
    List<PageGeometrySource> geometrySources = new ArrayList<>();
    int lineNo = 0;
    for (PaperPageEntity page : pages) {
      int start = lineNo + 1;
      String text = page.getTextContent();
      List<String> pageLines = new ArrayList<>();
      if (text != null) {
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
          if (!line.isBlank()) {
            lineNo++;
            String normalizedLine = line.stripTrailing();
            lines.add(normalizedLine);
            pageLines.add(normalizedLine);
          }
        }
      }
      pageRanges.add(new int[] {page.getPageNo(), start, lineNo});
      PageRecognitionGeometrySnapshot geometry = loadGeometry(
          page, assetsByPage.getOrDefault(page.getPageNo(), List.of()), pageLines);
      geometrySources.add(new PageGeometrySource(geometry, start));
    }
    if (lines.isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_PARSE_FAILED, "各页文本为空，无法切题");
    }
    AssetGenerationScope assetScope = resolveAssetGeneration(
        allAssets,
        geometrySources.stream().map(PageGeometrySource::snapshot).toList()
    );
    List<PaperAssetEntity> assets = assetScope.assets();
    List<String> generationWarnings = assetScope.missingAssetKeys().stream()
        .map(key -> "CURRENT_ASSET_MISSING: 当前页快照引用的资源 " + key + " 不存在")
        .toList();
    PaperFlowLog.event("PAPER_STRUCTURE_INPUT lines={}, pageRanges={}, assets={}, "
            + "currentAssets={}, geometrySources={}, generationWarnings={}",
        lines.size(), pageRanges.size(), allAssets.size(), assets.size(),
        geometrySources.size(), generationWarnings);

    Map<String, PaperAssetBinding> bindings = new LinkedHashMap<>();
    for (PageGeometrySource source : geometrySources) {
      bindings.putAll(ownershipResolver.resolve(source.snapshot(), source.pageLineStart()));
    }
    String numbered = buildNumbered(lines);
    String hash = sha256(numbered + "\n" + canonicalAssetGeometry(geometrySources)
        + "\nHIERARCHY=" + PaperQuestionGroupAssembler.HIERARCHY_SCHEMA_VERSION);
    PaperStructureSnapshot snapshot = loadCached(paper, hash);
    if (snapshot == null) {
      if (!allowModelCall) {
        PaperFlowLog.event("PAPER_STRUCTURE_CACHE_MISS_READ_ONLY hash={}", hash);
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "试卷结构缓存缺失，请重新切题后再查看");
      }
      PaperFlowLog.event("PAPER_STRUCTURE_CACHE_MISS hash={}", hash);
      BlockTreeDTO blocks = normalizeBlocks(
          callModelChunked(paperId, lines, pageRanges, assets, bindings), lines);
      snapshot = PaperStructureSnapshot.initial(blocks);
      storeSnapshot(paperId, hash, snapshot);
    } else {
      PaperFlowLog.event("PAPER_STRUCTURE_CACHE_HIT hash={}, schemaVersion={}",
          hash, snapshot.schemaVersion());
      snapshot = snapshot.withBlocks(normalizeBlocks(snapshot.blocks(), lines));
    }
    BuiltStructure assembled = assemble(paperId, lines, pageRanges, snapshot.blocks(), assets,
        bindings);
    List<String> builtWarnings = new ArrayList<>(generationWarnings);
    Map<Integer, AnswerKeyDTO> alignedKeys = alignedAnswerKeys(
        assembled.questions(), snapshot.answerKeys(), snapshot.questionFingerprints(),
        builtWarnings);
    BuiltStructure built = assembled.withAnswerKeys(alignedKeys)
        .withAdditionalWarnings(builtWarnings);
    PaperFlowLog.event("PAPER_STRUCTURE_END status=SUCCESS, questions={}, totalScore={}, "
            + "warnings={}, durationMs={}",
        built.questions().size(), built.totalScore(), built.warnings(), elapsedMs(startNanos));
    return built;
  }

  static AssetGenerationScope resolveAssetGeneration(
      List<PaperAssetEntity> allAssets,
      List<PageRecognitionGeometrySnapshot> snapshots
  ) {
    Set<String> currentAssetKeys = new LinkedHashSet<>();
    for (PageRecognitionGeometrySnapshot snapshot : snapshots == null
        ? List.<PageRecognitionGeometrySnapshot>of() : snapshots) {
      for (PageAssetGeometry asset : snapshot.assets()) {
        currentAssetKeys.add(asset.assetKey());
      }
    }
    List<PaperAssetEntity> currentAssets = allAssets.stream()
        .filter(asset -> currentAssetKeys.contains(asset.getAssetKey()))
        .toList();
    Set<String> persistedAssetKeys = allAssets.stream()
        .map(PaperAssetEntity::getAssetKey)
        .collect(Collectors.toSet());
    List<String> missingAssetKeys = currentAssetKeys.stream()
        .filter(key -> !persistedAssetKeys.contains(key))
        .toList();
    return new AssetGenerationScope(currentAssets, missingAssetKeys);
  }

  private PageRecognitionGeometrySnapshot loadGeometry(
      PaperPageEntity page,
      List<PaperAssetEntity> pageAssets,
      List<String> pageLines
  ) {
    PageGeometryInspector.Inspection inspection = PageGeometryInspector.inspect(
        page.getSource(), page.getAssetsJson(), pageAssets.size(), objectMapper);
    if (inspection.issue() == PageGeometryInspector.GeometryIssue.OK
        && inspection.snapshot() != null) {
      return inspection.snapshot();
    }
    if (inspection.issue() == PageGeometryInspector.GeometryIssue.INVALID) {
      if (inspection.failure() != null) {
        PaperFlowLog.error("PAGE_GEOMETRY_DECODE_FAILED paperId={}, pageNo={}",
            inspection.failure(), page.getPaperId(), page.getPageNo());
        log.warn("页几何快照解析失败: paperId={}, pageNo={}", page.getPaperId(),
            page.getPageNo(), inspection.failure());
      } else {
        PaperFlowLog.event("PAGE_GEOMETRY_INCOMPATIBLE paperId={}, pageNo={}, reason={}",
            page.getPaperId(), page.getPageNo(), inspection.reason());
      }
    }
    if (!pageAssets.isEmpty() && page.getSource() == PageSource.IMAGE) {
      throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
          "第 " + page.getPageNo() + " 页缺少几何快照，请重新识别该页");
    }
    return syntheticGeometry(page, pageAssets, pageLines);
  }

  private PageRecognitionGeometrySnapshot syntheticGeometry(
      PaperPageEntity page,
      List<PaperAssetEntity> pageAssets,
      List<String> pageLines
  ) {
    return TextPageGeometry.fromStoredPage(page.getPageNo(), pageAssets, pageLines);
  }

  private PaperStructureSnapshot loadCached(PracticePaperEntity paper, String hash) {
    if (paper.getStructureJson() == null || !hash.equals(paper.getStructureHash())) {
      return null;
    }
    try {
      PaperStructureSnapshot snapshot = objectMapper.readValue(
          paper.getStructureJson(), PaperStructureSnapshot.class);
      if (snapshot.blocks() != null) {
        PaperFlowLog.event("PAPER_STRUCTURE_CACHE_DECODED schemaVersion={}, blocks={}, "
                + "answerKeys={}",
            snapshot.schemaVersion(), snapshot.blocks().nodes() == null
                ? 0 : snapshot.blocks().nodes().size(),
            snapshot.answerKeys() == null ? 0 : snapshot.answerKeys().size());
        if (snapshot.schemaVersion() <= PaperStructureSnapshot.CURRENT_SCHEMA_VERSION) {
          return snapshot;
        }
        log.info("结构快照 schema 高于当前版本，重建缓存: paperId={}, oldVersion={}, newVersion={}",
            paper.getId(), snapshot.schemaVersion(),
            PaperStructureSnapshot.CURRENT_SCHEMA_VERSION);
        return null;
      }
    } catch (JacksonException e) {
      PaperFlowLog.error("PAPER_STRUCTURE_CACHE_DECODE_FAILED paperId={}", e, paper.getId());
      log.info("结构缓存格式已升级，将重新调用模型: paperId={}", paper.getId());
      return null;
    }
    return null;
  }

  private void storeSnapshot(Long paperId, String hash, PaperStructureSnapshot snapshot) {
    String json;
    try {
      json = objectMapper.writeValueAsString(snapshot);
    } catch (JacksonException e) {
      PaperFlowLog.error("PAPER_STRUCTURE_SNAPSHOT_SERIALIZE_FAILED paperId={}, hash={}",
          e, paperId, hash);
      log.warn("结构结果序列化失败，跳过缓存: paperId={}", paperId);
      return;
    }
    PaperFlowLog.event("PAPER_STRUCTURE_SNAPSHOT_SAVE_BEGIN paperId={}, hash={}, jsonChars={}",
        paperId, hash, json.length());
    PaperFlowLog.raw("PAPER_STRUCTURE_SNAPSHOT_JSON paperId=" + paperId, json);
    transactionalExecutor.run(() -> resumeRepository.findById(paperId).ifPresent(paper -> {
      paper.setStructureJson(json);
      paper.setStructureHash(hash);
      resumeRepository.save(paper);
    }));
    PaperFlowLog.event("PAPER_STRUCTURE_SNAPSHOT_SAVE_END paperId={}, hash={}, jsonChars={}",
        paperId, hash, json.length());
  }

  /**
   * 保存答案键并返回应用后的结构。模型调用必须在事务外完成，本方法只做短暂落库。
   */
  public BuiltStructure applyAndStoreAnswerKeys(Long paperId, BuiltStructure built,
                                                Map<Integer, AnswerKeyDTO> answerKeys) {
    PaperFlowLog.event("ANSWER_KEY_SNAPSHOT_APPLY_BEGIN paperId={}, answerKeys={}",
        paperId, answerKeys == null ? 0 : answerKeys.size());
    PracticePaperEntity paper = resumeRepository.findById(paperId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_NOT_FOUND, "试卷不存在"));
    if (paper.getStructureJson() == null || paper.getStructureHash() == null) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "试卷结构快照不存在");
    }
    try {
      PaperStructureSnapshot current = objectMapper.readValue(
          paper.getStructureJson(), PaperStructureSnapshot.class);
      Map<Integer, String> fingerprints = built.questions().stream()
          .collect(Collectors.toMap(
              InterviewQuestionDTO::questionIndex,
              QuestionFingerprint::of,
              (left, right) -> left,
              LinkedHashMap::new));
      PaperStructureSnapshot updated =
          current.withAnswerKeysAndFingerprints(answerKeys, fingerprints);
      storeSnapshot(paperId, paper.getStructureHash(), updated);
      BuiltStructure applied = built.withAnswerKeys(updated.answerKeys());
      PaperFlowLog.event("ANSWER_KEY_SNAPSHOT_APPLY_END paperId={}, answerKeys={}",
          paperId, updated.answerKeys() == null ? 0 : updated.answerKeys().size());
      return applied;
    } catch (JacksonException e) {
      PaperFlowLog.error("ANSWER_KEY_SNAPSHOT_APPLY_FAILED paperId={}", e, paperId);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存答案键失败", e);
    }
  }

  private BlockTreeDTO callModel(String numberedText, String assetInfo) {
    String system = systemPromptTemplate.render()
        + "\n\n下面是输出字段说明（不是答案，不要原样输出 $schema / properties / required）：\n"
        + outputConverter.getFormat();
    String user = userPromptTemplate.render(Map.of(
        "paperText", numberedText,
        "assetInfo", assetInfo
    ));
    ChatClient client = llmProviderRegistry.getPlainChatClient();
    BlockTreeDTO blocks = structuredOutputInvoker.invokeWithoutSchemaValidation(
        client,
        system,
        user,
        outputConverter,
        ErrorCode.PAPER_EXTRACTION_FAILED,
        "试卷结构化失败：",
        "试卷结构化",
        log,
        content -> PaperFlowLog.raw("PAPER_STRUCTURE_MODEL_RESPONSE", content),
        result -> result != null && result.nodes() != null && !result.nodes().isEmpty(),
        "不要返回 JSON Schema（$schema / type / properties / required）或示例题；"
            + "必须返回包含 nodes 数组的数据对象："
            + "{\"subjectKey\":...,\"totalScore\":...,\"nodes\":[...],\"warnings\":[...]}"
    );
    PaperFlowLog.json(objectMapper, "PAPER_STRUCTURE_MODEL_PARSED", blocks);
    if (blocks == null || blocks.nodes() == null || blocks.nodes().isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_NO_QUESTION_FOUND, "模型没有返回结构节点");
    }
    return blocks;
  }

  /**
   * 分块调用：整卷一次输出 40~50 题的 JSON 很容易被截断，
   * 按"每块约 2~3 页 / 2800 字"调用，行号保持全卷连续，最后合并节点。
   */
  private BlockTreeDTO callModelChunked(Long paperId, List<String> lines, List<int[]> pageRanges,
                                        List<PaperAssetEntity> assets,
                                        Map<String, PaperAssetBinding> bindings) {
    List<ChunkPlan> plans = planChunks(lines, pageRanges);
    List<ChunkResult> results = runChunks(paperId, plans, lines, assets, bindings);
    return assembleChunks(results);
  }

  static List<ChunkPlan> planChunks(List<String> lines, List<int[]> pageRanges) {
    List<ChunkPlan> plans = new ArrayList<>();
    int chunkStart = -1;
    int chunkEnd = -1;
    int charCount = 0;

    for (int[] range : pageRanges) {
      int pageStart = Math.max(1, range[1]);
      int pageEnd = Math.max(pageStart - 1, range[2]);
      if (pageEnd < pageStart) {
        continue;
      }
      int pageChars = 0;
      for (int i = pageStart; i <= pageEnd; i++) {
        pageChars += lines.get(i - 1).length();
      }
      boolean exceedsSoftLimit = chunkStart >= 0
          && charCount + pageChars > SOFT_CHUNK_CHARS;
      boolean exceedsHardLimit = chunkStart >= 0
          && charCount + pageChars > HARD_CHUNK_CHARS;
      if (exceedsHardLimit
          || exceedsSoftLimit && isSemanticBoundaryStart(lines, pageStart)) {
        PaperFlowLog.event("PAPER_STRUCTURE_CHUNK_SPLIT start={}, end={}, chars={}, pages={}, "
                + "reason={}",
            chunkStart, chunkEnd, charCount, pagesInRange(pageRanges, chunkStart, chunkEnd),
            exceedsHardLimit ? "HARD_LIMIT" : "SEMANTIC_BOUNDARY");
        plans.add(new ChunkPlan(chunkStart, chunkEnd, charCount));
        chunkStart = -1;
        chunkEnd = -1;
        charCount = 0;
      }
      if (chunkStart < 0) {
        chunkStart = pageStart;
      }
      chunkEnd = pageEnd;
      charCount += pageChars;
    }
    if (chunkStart >= 0 && chunkEnd >= chunkStart) {
      PaperFlowLog.event("PAPER_STRUCTURE_CHUNK_SPLIT start={}, end={}, chars={}, pages={}, "
              + "reason=TAIL",
          chunkStart, chunkEnd, charCount, pagesInRange(pageRanges, chunkStart, chunkEnd));
      plans.add(new ChunkPlan(chunkStart, chunkEnd, charCount));
    }
    return plans;
  }

  private List<ChunkResult> runChunks(Long paperId, List<ChunkPlan> plans, List<String> lines,
                                      List<PaperAssetEntity> assets,
                                      Map<String, PaperAssetBinding> bindings) {
    if (plans.isEmpty()) {
      return List.of();
    }
    List<ChunkResult> results = new ArrayList<>(plans.size());
    int submitted = 0;
    int emptyRounds = 0;
    while (submitted < plans.size()) {
      List<Future<ChunkResult>> futures = new ArrayList<>();
      List<ChunkPlan> roundPlans = new ArrayList<>();
      while (submitted < plans.size()) {
        ChunkPlan plan = plans.get(submitted);
        try {
          futures.add(structureExecutor.submit(
              () -> callChunk(paperId, lines, plan.startLine(), plan.endLine(),
                  assets, bindings)));
          roundPlans.add(plan);
          submitted++;
        } catch (ExecutorRejectedException e) {
          PaperFlowLog.warn("PAPER_STRUCTURE_CHUNK_SUBMIT_REJECTED executor={}, submitted={}, "
                  + "active={}, queue={}",
              structureExecutor.name(), submitted, structureExecutor.activeCount(),
              structureExecutor.queueSize());
          break;
        }
      }
      if (futures.isEmpty()) {
        if (structureExecutor.isShutdown() || emptyRounds >= MAX_EMPTY_SUBMIT_ROUNDS) {
          for (; submitted < plans.size(); submitted++) {
            results.add(skippedChunk(plans.get(submitted)));
          }
          break;
        }
        emptyRounds++;
        sleepBackpressure();
        continue;
      }
      emptyRounds = 0;
      for (Future<ChunkResult> future : futures) {
        results.add(awaitChunk(future));
      }
    }
    return results;
  }

  private ChunkResult skippedChunk(ChunkPlan plan) {
    log.warn("结构分块等待执行器超时，跳过: lines={}-{}", plan.startLine(), plan.endLine());
    return new ChunkResult(List.of(),
        List.of("第 " + plan.startLine() + "–" + plan.endLine()
            + " 行结构化等待执行器超时，已跳过，请在校对页补录"),
        null, false);
  }

  private void sleepBackpressure() {
    try {
      Thread.sleep(BACKPRESSURE_SLEEP_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
          "结构切题等待执行器被中断，请稍后重试", e);
    }
  }

  private ChunkResult awaitChunk(Future<ChunkResult> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
          "结构切题等待被中断，请稍后重试", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "结构切题失败", cause);
    }
  }

  static BlockTreeDTO assembleChunks(List<ChunkResult> results) {
    List<BlockNodeDTO> allNodes = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    String subjectKey = null;
    int chunkCount = 0;
    for (ChunkResult result : results) {
      allNodes.addAll(result.nodes());
      warnings.addAll(result.warnings());
      if ((subjectKey == null || subjectKey.isBlank())
          && result.subjectKey() != null && !result.subjectKey().isBlank()) {
        subjectKey = result.subjectKey();
      }
      if (result.success()) {
        chunkCount++;
      }
    }
    if (allNodes.isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
          "所有分块的结构化都失败了，请稍后重试");
    }
    if (chunkCount > 1) {
      warnings.add("试卷较长，已分 " + chunkCount + " 块结构化；跨块材料请在校对页重点核对");
    }
    allNodes.sort(Comparator.comparingInt(BlockNodeDTO::startLine));
    return new BlockTreeDTO(subjectKey, null, allNodes, warnings);
  }

  record ChunkPlan(int startLine, int endLine, int charCount) {
  }

  /**
   * 软阈值只在新的部分/大题/阅读小节开始时拆分；普通续页继续并入当前块，
   * 避免把一组材料和小题从中间拆开。
   */
  static boolean isSemanticBoundaryStart(List<String> lines, int lineNo) {
    if (lineNo < 1 || lineNo > lines.size()) {
      return false;
    }
    String line = lines.get(lineNo - 1).replaceAll("\\s+", "");
    return line.matches("^第[一二三四五六七八九十0-9]+部分.*")
        || line.matches("^[一二三四五六七八九十百]+[、.．]?.*")
        || line.matches("^[（(][一二三四五六七八九十]+[）)].*")
        || line.startsWith("参考答案")
        || line.contains("答案与解析");
  }

  private ChunkResult callChunk(Long paperId, List<String> lines, int start, int end,
                                List<PaperAssetEntity> assets,
                                Map<String, PaperAssetBinding> bindings) {
    String rangeText = numberedRange(lines, start, end);
    PaperFlowLog.event("PAPER_STRUCTURE_CHUNK_BEGIN start={}, end={}, lines={}, chars={}, "
            + "assets={}, bindings={}",
        start, end, end - start + 1, rangeText.length(), assets.size(), bindings.size());
    try {
      analysisPacer.awaitAvailable(paperId);
      BlockTreeDTO part = callModel(
          rangeText, buildAssetInfo(assets, bindings));
      ChunkResult result = new ChunkResult(
          part.nodes() == null ? List.of() : part.nodes(),
          part.warnings() == null ? List.of() : part.warnings(),
          part.subjectKey(),
          true
      );
      PaperFlowLog.event("PAPER_STRUCTURE_CHUNK_END status=SUCCESS, start={}, end={}, "
              + "nodes={}, warnings={}, subjectKey={}",
          start, end, result.nodes().size(), result.warnings(), result.subjectKey());
      return result;
    } catch (Exception e) {
      PaperFlowLog.error("PAPER_STRUCTURE_CHUNK_END status=FAILED, start={}, end={}",
          e, start, end);
      log.warn("结构分块失败，跳过: lines={}-{}", start, end, e);
      return new ChunkResult(List.of(),
          List.of("第 " + start + "–" + end + " 行结构化失败，已跳过，请在校对页补录"), null, false);
    }
  }

  record ChunkResult(List<BlockNodeDTO> nodes, List<String> warnings,
                     String subjectKey, boolean success) {
  }

  private String numberedRange(List<String> lines, int start, int end) {
    StringBuilder sb = new StringBuilder();
    for (int i = start; i <= end && i <= lines.size(); i++) {
      sb.append('L').append(String.format("%04d", i)).append(' ')
          .append(lines.get(i - 1)).append('\n');
    }
    return sb.toString();
  }

  private static int pagesInRange(List<int[]> pageRanges, int start, int end) {
    return (int) pageRanges.stream()
        .filter(range -> range[2] >= start && range[1] <= end)
        .count();
  }

  private int countNodes(PaperNode node, String type) {
    if (node == null) {
      return 0;
    }
    int count = type.equals(node.type()) ? 1 : 0;
    for (PaperNode child : node.safeChildren()) {
      count += countNodes(child, type);
    }
    return count;
  }

  private BuiltStructure assemble(
      Long paperId,
      List<String> lines,
      List<int[]> pageRanges,
      BlockTreeDTO blocks,
      List<PaperAssetEntity> allAssets,
      Map<String, PaperAssetBinding> bindings
  ) {
    List<BlockNodeDTO> nodes = new ArrayList<>(blocks.nodes());
    nodes.sort((a, b) -> Integer.compare(a.startLine(), b.startLine()));

    List<String> warnings = new ArrayList<>();
    if (blocks.warnings() != null) {
      warnings.addAll(blocks.warnings());
    }
    PaperQuestionGroupAssembler.GroupAssembly groups = questionGroupAssembler.assemble(nodes);
    warnings.addAll(groups.warnings());
    PaperFlowLog.event("PAPER_STRUCTURE_ASSEMBLE_BEGIN paperId={}, nodes={}, lines={}, "
            + "groupPlans={}, bindings={}, assets={}, warnings={}",
        paperId, nodes.size(), lines.size(), groups.plans().size(), bindings.size(),
        allAssets.size(), warnings);
    Map<BlockNodeDTO, QuestionGroupPlan> planByNode = new HashMap<>();
    for (QuestionGroupPlan plan : groups.plans()) {
      plan.materials().forEach(node -> planByNode.put(node, plan));
      plan.questions().forEach(node -> planByNode.put(node, plan));
    }
    boolean[] covered = new boolean[lines.size() + 1];

    NodeBuilder root = new NodeBuilder("PAPER", "试卷");
    NodeBuilder currentPart = null;
    NodeBuilder currentSection = null;
    NodeBuilder currentGroup = null;
    int questionIndex = 0;
    Map<Integer, String> questionKinds = new LinkedHashMap<>();
    boolean afterAnswerKey = false;
    Map<Integer, AnswerKeyDTO> officialKeys = new LinkedHashMap<>();

    for (BlockNodeDTO node : nodes) {
      int start = Math.max(1, Math.min(lines.size(), node.startLine()));
      int end = Math.max(1, Math.min(lines.size(), node.endLine()));
      if (end < start) {
        int tmp = start;
        start = end;
        end = tmp;
      }
      for (int i = start; i <= end; i++) {
        covered[i] = true;
      }
      String type = node.type() == null ? "" : node.type().toUpperCase();
      String text = joinLines(lines, start, end);
      if (afterAnswerKey) {
        continue;
      }
      if ("ANSWER_KEY".equals(type)) {
        officialKeys.putAll(parseOfficialAnswerKey(text, warnings));
        if (isDefinitelyTrailingAnswerKey(nodes, node)) {
          afterAnswerKey = true;
        } else {
          warnings.add("检测到答案区但未确认位于卷末，后续题目仍保留");
        }
        continue;
      }
      QuestionGroupPlan groupPlan = planByNode.get(node);
      if (groupPlan != null) {
        if (currentGroup == null || !groupPlan.groupId().equals(currentGroup.id)) {
          currentGroup = new NodeBuilder("QUESTION_GROUP", "材料题组", groupPlan.groupId());
          currentGroup.startLine = groupPlan.startLine();
          currentGroup.endLine = groupPlan.endLine();
          (currentSection != null ? currentSection
              : currentPart != null ? currentPart : root).children.add(currentGroup);
        }
      } else if ("MATERIAL".equals(type) || isQuestionType(type)) {
        currentGroup = null;
      }
      switch (type) {
        case "PART" -> {
          NodeBuilder part = new NodeBuilder("SECTION", defaultText(node.title(), firstLine(lines, start)));
          part.startLine = start;
          part.endLine = end;
          root.children.add(part);
          currentPart = part;
          currentSection = null;
          currentGroup = null;
        }
        case "SECTION" -> {
          NodeBuilder section = new NodeBuilder("SECTION",
              defaultText(node.title(), firstLine(lines, start)));
          section.startLine = start;
          section.endLine = end;
          (currentPart != null ? currentPart : root).children.add(section);
          currentSection = section;
          currentGroup = null;
        }
        case "MATERIAL" -> {
          NodeBuilder material = new NodeBuilder("MATERIAL", defaultText(node.title(), "材料"));
          material.startLine = start;
          material.endLine = end;
          material.content = text;
          NodeBuilder parent = currentGroup != null ? currentGroup
              : currentSection != null ? currentSection
              : currentPart != null ? currentPart : root;
          parent.children.add(material);
        }
        case "EXCERPT" -> {
          // 题目旁重复展示的段落摘录：保留行覆盖，但不切换后续题目的材料父节点。
        }
        case "INSTRUCTION" -> {
          NodeBuilder instruction = new NodeBuilder("INSTRUCTION", "说明");
          instruction.startLine = start;
          instruction.endLine = end;
          instruction.content = text;
          (currentSection != null ? currentSection : root).children.add(instruction);
        }
        case "QUESTION", "SUB_QUESTION", "BLANK" -> {
          questionKinds.put(questionIndex, type);
          InterviewQuestionDTO dto = toQuestion(questionIndex++, node, text, start, end, pageRanges,
              warnings);
          NodeBuilder question = new NodeBuilder("QUESTION", null);
          question.startLine = start;
          question.endLine = end;
          question.question = dto;
          NodeBuilder parent = currentGroup != null ? currentGroup
              : currentSection != null ? currentSection
              : currentPart != null ? currentPart : root;
          parent.children.add(question);
        }
        default -> log.debug("忽略未知节点类型: {}", type);
      }
    }

    List<InterviewQuestionDTO> extraQuestions = new ArrayList<>();
    ensureTaskQuestions(root, new int[] {questionIndex}, extraQuestions, warnings);
    if (questionIndex == 0 && extraQuestions.isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_NO_QUESTION_FOUND, "结构模型没有识别到题目");
    }

    int uncovered = 0;
    for (int i = 1; i <= lines.size(); i++) {
      if (!covered[i]) {
        uncovered++;
      }
    }
    if (uncovered > lines.size() * 0.1) {
      warnings.add("有 " + uncovered + " 行未被任何节点覆盖，请在校对页重点核对");
    }

    int[] idSeq = {0};
    List<PaperNode> rootChildren = new ArrayList<>();
    for (NodeBuilder child : root.children) {
      PaperNode converted = toPaperNode(child, rootChildren.size() + 1, idSeq);
      if (converted != null) {
        rootChildren.add(converted);
      }
    }
    PaperNode paperRoot = new PaperNode("n0", PaperNode.TYPE_PAPER, 0, 0, false,
        defaultText(blocks.subjectKey(), "试卷"), null, null,
        new PaperNode.PaperMeta(null, null, null), null, null, rootChildren);
    PaperTree groupedTree = subQuestionGrouper.group(
        PaperTree.recalculate(new PaperTree(PaperTree.CURRENT_VERSION, paperRoot)), questionKinds);
    PaperAssetBindingService.BindingResult bindingResult = assetBindingService.bind(
        paperId, groupedTree.root(), bindings, allAssets);
    warnings.addAll(bindingResult.warnings());
    PaperTree tree = PaperTree.recalculate(
        new PaperTree(groupedTree.version(), bindingResult.root()));
    warnings.addAll(PaperAssetOwnershipValidator.validate(tree.root()));

    List<InterviewQuestionDTO> questions = new ArrayList<>(tree.flattenQuestions());
    if (!officialKeys.isEmpty()) {
      Set<Integer> indexes = questions.stream()
          .map(InterviewQuestionDTO::questionIndex)
          .collect(Collectors.toSet());
      Map<Integer, AnswerKeyDTO> usable = officialKeys.entrySet().stream()
          .filter(entry -> indexes.contains(entry.getKey()))
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
              (left, right) -> left, LinkedHashMap::new));
      if (!usable.isEmpty()) {
        questions = questions.stream()
            .map(question -> usable.containsKey(question.questionIndex())
                ? question.withAnswerKey(usable.get(question.questionIndex()))
                : question)
            .collect(Collectors.toCollection(ArrayList::new));
        tree = tree.withQuestions(questions);
        warnings.add("已从卷末答案区回填 " + usable.size() + " 题参考答案");
      } else {
        warnings.add("卷末答案区的题号与题目树不匹配，已忽略");
      }
    }
    int totalScore = questions.stream()
        .mapToInt(question -> question.fullScore() == null ? 0 : question.fullScore())
        .sum();
    String subject = blocks.subjectKey() != null && SUBJECTS.contains(blocks.subjectKey())
        ? blocks.subjectKey() : InterviewDefaults.SKILL_ID;
    PaperFlowLog.event("PAPER_STRUCTURE_ASSEMBLE_END paperId={}, questions={}, totalScore={}, "
            + "materialNodes={}, questionGroups={}, assetWarnings={}, warnings={}",
        paperId, questions.size(), totalScore, countNodes(tree.root(), PaperNode.TYPE_MATERIAL),
        countNodes(tree.root(), PaperNode.TYPE_QUESTION_GROUP), bindingResult.warnings(), warnings);
    PaperFlowLog.json(objectMapper, "PAPER_STRUCTURE_FINAL_TREE", tree);
    return new BuiltStructure(subject, tree, questions, totalScore, warnings);
  }

  private BlockTreeDTO normalizeBlocks(BlockTreeDTO blocks, List<String> lines) {
    if (blocks == null || blocks.nodes() == null) {
      return blocks;
    }
    PaperFlowLog.event("PAPER_STRUCTURE_NORMALIZE_BEGIN nodes={}, lines={}, subjectKey={}",
        blocks.nodes().size(), lines.size(), blocks.subjectKey());
    List<BlockNodeDTO> folded = PaperSubQuestionFoldNormalizer.normalize(blocks.nodes(), lines);
    List<BlockNodeDTO> bounded = PaperBlockBoundaryNormalizer.normalize(folded, lines);
    List<BlockNodeDTO> hierarchy = PaperMaterialHierarchyNormalizer.normalize(bounded, lines);
    BlockTreeDTO normalized = new BlockTreeDTO(
        blocks.subjectKey(), blocks.totalScore(), hierarchy, blocks.warnings());
    PaperFlowLog.event("PAPER_STRUCTURE_NORMALIZE_END before={}, after={}, warnings={}",
        blocks.nodes().size(), normalized.nodes() == null ? 0 : normalized.nodes().size(),
        normalized.warnings());
    PaperFlowLog.json(objectMapper, "PAPER_STRUCTURE_NORMALIZED_BLOCKS", normalized);
    return normalized;
  }

  static Map<Integer, AnswerKeyDTO> parseOfficialAnswerKey(String text, List<String> warnings) {
    Map<Integer, AnswerKeyDTO> keys = new LinkedHashMap<>();
    if (text == null || text.isBlank()) {
      return keys;
    }
    Pattern linePattern = Pattern.compile("^\\s*(\\d{1,3})\\s*[.、:：)）]\\s*(.+)$");
    Pattern prefix = Pattern.compile("^(答案|参考答案|正确答案)\\s*[:：]?\\s*");
    for (String line : text.split("\\R")) {
      Matcher matcher = linePattern.matcher(line);
      if (!matcher.matches()) {
        continue;
      }
      int index = Integer.parseInt(matcher.group(1)) - 1;
      String answer = prefix.matcher(matcher.group(2).trim()).replaceFirst("").trim();
      if (index < 0 || answer.isBlank()) {
        continue;
      }
      keys.putIfAbsent(index, new AnswerKeyDTO(
          index, answer, Map.of(), null, List.of(), null, null, false, 90,
          AnswerKeyDTO.SOURCE_OFFICIAL));
    }
    if (!keys.isEmpty()) {
      PaperFlowLog.event("PAPER_STRUCTURE_OFFICIAL_ANSWER_KEY parsed={}", keys.size());
    } else if (text.lines().anyMatch(line -> !line.isBlank())) {
      warnings.add("官方答案区解析不完整，未识别到“题号 + 答案”行");
    }
    return keys;
  }

  static boolean isDefinitelyTrailingAnswerKey(List<BlockNodeDTO> nodes, BlockNodeDTO current) {
    int start = nodes.indexOf(current);
    if (start < 0) {
      return true;
    }
    for (int i = start + 1; i < nodes.size(); i++) {
      String type = nodes.get(i).type() == null ? "" : nodes.get(i).type().toUpperCase();
      if (isQuestionType(type)) {
        return false;
      }
    }
    return true;
  }

  static Map<Integer, AnswerKeyDTO> alignedAnswerKeys(
      List<InterviewQuestionDTO> questions,
      Map<Integer, AnswerKeyDTO> storedKeys,
      Map<Integer, String> storedFingerprints,
      List<String> warnings
  ) {
    if (storedKeys == null || storedKeys.isEmpty()) {
      return Map.of();
    }
    Map<Integer, String> fingerprints = storedFingerprints == null
        ? Map.of() : storedFingerprints;
    Map<Integer, AnswerKeyDTO> kept = new LinkedHashMap<>();
    int discarded = 0;
    for (InterviewQuestionDTO question : questions) {
      AnswerKeyDTO key = storedKeys.get(question.questionIndex());
      if (key == null) {
        continue;
      }
      String stored = fingerprints.get(question.questionIndex());
      // 旧快照没有指纹时保守复用；有新指纹且不一致才重算该题。
      if (stored != null && !stored.equals(QuestionFingerprint.of(question))) {
        discarded++;
        continue;
      }
      kept.put(question.questionIndex(), key);
    }
    if (discarded > 0) {
      PaperFlowLog.event("ANSWER_KEY_FINGERPRINT_CHANGED discarded={}, kept={}",
          discarded, kept.size());
      warnings.add("有 " + discarded + " 道题题干已变化，已重新生成这些题的答案键");
    }
    return kept;
  }

  static Map<Integer, AnswerKeyDTO> alignedAnswerKeys(
      List<InterviewQuestionDTO> questions,
      Map<Integer, AnswerKeyDTO> storedKeys,
      List<String> warnings
  ) {
    return alignedAnswerKeys(questions, storedKeys, Map.of(), warnings);
  }

  private InterviewQuestionDTO toQuestion(int index, BlockNodeDTO node, String text,
                                          int startLine, int endLine, List<int[]> pageRanges,
                                          List<String> warnings) {
    List<QuestionOption> options = new ArrayList<>();
    if (node.options() != null) {
      for (BlockOptionDTO option : node.options()) {
        if (option != null && option.key() != null) {
          options.add(new QuestionOption(option.key(), option.text()));
        }
      }
    }
    String questionType = normalizeQuestionType(node.questionType(), text, options);
    if ("IMAGE".equalsIgnoreCase(node.optionType())
        && !isChoiceType(questionType)) {
      questionType = InterviewQuestionDTO.TYPE_SINGLE_CHOICE;
    }
    Integer fullScore = resolveFullScore(node.fullScore(), text, questionType);
    if ((node.fullScore() == null || node.fullScore() <= 0)
        && !SCORE_PATTERN.matcher(text == null ? "" : text).find()) {
      String warning = "部分题目未标注分值，已按题型推断";
      if (!warnings.contains(warning)) {
        warnings.add(warning);
      }
    }
    String category = node.category() == null || node.category().isBlank()
        ? "未分类" : node.category();
    Integer[] pages = pagesOf(startLine, endLine, pageRanges);
    String structureAnswer = AnswerKeySupport.isMissing(node.referenceAnswer())
        ? null : node.referenceAnswer().trim();
    return new InterviewQuestionDTO(
        index,
        text,
        category,
        category,
        null,
        null,
        null,
        null,
        false,
        null,
        structureAnswer == null ? "暂缺" : structureAnswer,
        node.keyPoints() == null ? List.of() : node.keyPoints(),
        node.scoringRubric(),
        null,
        fullScore,
        questionType,
        options.isEmpty() ? null : options,
        node.analysis(),
        pages[0],
        pages[1],
        null,
        structureAnswer == null ? null : AnswerKeyDTO.SOURCE_STRUCTURE,
        null,
        false
    );
  }

  public BuiltStructure applyAnswerKeys(BuiltStructure built,
                                        Map<Integer, AnswerKeyDTO> answerKeys) {
    return built.withAnswerKeys(answerKeys);
  }

  /**
   * 给已有题目树叠加最新答案键，不重新组装材料、图片和层级。
   * 用于已交卷后再次作答，避免为了刷新答案键重复执行整条结构流水线。
   */
  public PaperTree applyStoredAnswerKeys(Long paperId, PaperTree tree) {
    PracticePaperEntity paper = resumeRepository.findById(paperId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_NOT_FOUND, "试卷不存在"));
    if (paper.getStructureJson() == null || paper.getStructureJson().isBlank()) {
      return tree;
    }
    try {
      PaperStructureSnapshot snapshot = objectMapper.readValue(
          paper.getStructureJson(), PaperStructureSnapshot.class);
      if (snapshot.answerKeys() == null || snapshot.answerKeys().isEmpty()) {
        return tree;
      }
      List<InterviewQuestionDTO> questions = tree.flattenQuestions().stream()
          .map(question -> {
            AnswerKeyDTO key = snapshot.answerKeys().get(question.questionIndex());
            return key == null ? question
                : question.withAnswerKey(AnswerKeyPreparationService.concise(key));
          })
          .toList();
      return tree.withQuestions(questions);
    } catch (JacksonException e) {
      PaperFlowLog.error("PAPER_STRUCTURE_ANSWER_KEY_READ_FAILED paperId={}",
          e, paperId);
      log.warn("读取最新答案键失败，继续使用原题目树: paperId={}", paperId);
      return tree;
    }
  }

  private PaperNode toPaperNode(NodeBuilder builder, int order, int[] seq) {
    List<PaperNode> children = new ArrayList<>();
    for (NodeBuilder child : builder.children) {
      PaperNode converted = toPaperNode(child, children.size() + 1, seq);
      if (converted != null) {
        children.add(converted);
      }
    }
    if ("SECTION".equals(builder.type) && children.isEmpty()) {
      return null;
    }
    String id = builder.id != null ? builder.id : "n" + (++seq[0]);
    if ("QUESTION".equals(builder.type) && builder.question != null) {
      InterviewQuestionDTO question = builder.question;
      return new PaperNode("q" + (question.questionIndex() + 1), PaperNode.TYPE_QUESTION, order,
          0, children.isEmpty(), null, null, null,
          new PaperNode.PaperMeta(question.fullScore(), 1, null, builder.assetIds,
              builder.startLine, builder.endLine),
          question.questionIndex(), question, children);
    }
    if ("MATERIAL".equals(builder.type)) {
      return new PaperNode(id, PaperNode.TYPE_MATERIAL, order, 0, children.isEmpty(),
          builder.title, null, builder.content,
          new PaperNode.PaperMeta(null, null, null, builder.assetIds,
              builder.startLine, builder.endLine),
          null, null, children);
    }
    if ("INSTRUCTION".equals(builder.type)) {
      return new PaperNode(id, PaperNode.TYPE_INSTRUCTION, order, 0, true,
          null, null, builder.content,
          new PaperNode.PaperMeta(null, null, null, List.of(),
              builder.startLine, builder.endLine),
          null, null, List.of());
    }
    if ("QUESTION_GROUP".equals(builder.type)) {
      return new PaperNode(id, PaperNode.TYPE_QUESTION_GROUP, order, 0, false,
          builder.title, null, null,
          new PaperNode.PaperMeta(null, null, null, List.of(),
              builder.startLine, builder.endLine),
          null, null, children);
    }
    return new PaperNode(id, PaperNode.TYPE_SECTION, order, 0, false,
        builder.title, null, null,
        new PaperNode.PaperMeta(null, null, null, List.of(),
            builder.startLine, builder.endLine),
        null, null, children);
  }

  private boolean isChoiceType(String questionType) {
    return InterviewQuestionDTO.TYPE_SINGLE_CHOICE.equals(questionType)
        || InterviewQuestionDTO.TYPE_MULTI_CHOICE.equals(questionType);
  }

  private static boolean isQuestionType(String type) {
    return "QUESTION".equals(type) || "SUB_QUESTION".equals(type) || "BLANK".equals(type);
  }

  /**
   * 只有 MATERIAL、没有任何 QUESTION 的小节，通常是一个以卡片/表格呈现的作答题。
   * 兜底生成一道题，避免用户看得到材料却无法作答。
   */
  private void ensureTaskQuestions(NodeBuilder node, int[] nextIndex,
                                   List<InterviewQuestionDTO> extraQuestions, List<String> warnings) {
    for (NodeBuilder child : node.children) {
      if ("SECTION".equals(child.type) && !hasQuestionDescendant(child)) {
        NodeBuilder material = firstMaterial(child);
        if (material != null && material.content != null && !material.content.isBlank()) {
          String title = defaultText(child.title, "作答题");
          String questionType = normalizeQuestionType(null, title + "\n" + material.content,
              List.of());
          InterviewQuestionDTO dto = new InterviewQuestionDTO(
              nextIndex[0]++,
              material.content,
              title,
              title,
              null,
              null,
              null,
              null,
              false,
              null,
              "暂缺",
              List.of(),
              null,
              null,
              resolveFullScore(null, title, questionType),
              questionType,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              false
          );
          NodeBuilder question = new NodeBuilder("QUESTION", null);
          question.startLine = material.startLine;
          question.endLine = material.endLine;
          question.question = dto;
          question.assetIds = material.assetIds;
          material.children.add(question);
          extraQuestions.add(dto);
          warnings.add("小节「" + title + "」只有材料没有题目，已按一道作答题生成，请核对");
        }
      }
      ensureTaskQuestions(child, nextIndex, extraQuestions, warnings);
    }
  }

  private boolean hasQuestionDescendant(NodeBuilder node) {
    for (NodeBuilder child : node.children) {
      if ("QUESTION".equals(child.type) || hasQuestionDescendant(child)) {
        return true;
      }
    }
    return false;
  }

  private NodeBuilder firstMaterial(NodeBuilder node) {
    for (NodeBuilder child : node.children) {
      if ("MATERIAL".equals(child.type)) {
        return child;
      }
      NodeBuilder nested = firstMaterial(child);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  /**
   * 模型常返回 multiple_choice / fill_in_blank 这类变体，统一到系统枚举。
   */
  private String normalizeQuestionType(String raw, String text, List<QuestionOption> options) {
    String normalized = raw == null ? "" : raw.trim().toUpperCase()
        .replace('-', '_').replace(' ', '_');
    String safeText = text == null ? "" : text;
    boolean multiHint = safeText.contains("多选")
        || safeText.contains("至少两项") || safeText.contains("至少两个")
        || safeText.contains("选出所有") || safeText.contains("选出全部");
    boolean writingHint = safeText.contains("作文") || safeText.contains("习作")
        || safeText.contains("写一篇") || safeText.contains("题目自拟")
        || safeText.contains("不少于") || safeText.contains("围绕") && safeText.contains("写一段话");
    boolean shortHint = safeText.contains("为什么") || safeText.contains("谈谈")
        || safeText.contains("分析") || safeText.contains("概括")
        || safeText.contains("翻译") || safeText.contains("改为")
        || safeText.contains("写出") || safeText.contains("说明理由")
        || safeText.contains("体会") || safeText.contains("仿写")
        || safeText.contains("描述") || safeText.contains("想象")
        || safeText.contains("制作");
    boolean fillHint = safeText.contains("填空") || safeText.contains("填序号")
        || safeText.contains("____") || safeText.contains("（ ）");

    if (options.size() >= 2) {
      return multiHint
          ? InterviewQuestionDTO.TYPE_MULTI_CHOICE : InterviewQuestionDTO.TYPE_SINGLE_CHOICE;
    }
    if (writingHint) {
      return InterviewQuestionDTO.TYPE_WRITING;
    }
    if (shortHint) {
      return InterviewQuestionDTO.TYPE_SHORT_ANSWER;
    }
    if (fillHint) {
      return InterviewQuestionDTO.TYPE_FILL_BLANK;
    }
    return switch (normalized) {
      case "SINGLE_CHOICE", "MULTIPLE_CHOICE", "MULTI_CHOICE", "MULTIPLE_CHOICES",
           "CHOICE", "CHOOSE" -> multiHint
          ? InterviewQuestionDTO.TYPE_MULTI_CHOICE : InterviewQuestionDTO.TYPE_SINGLE_CHOICE;
      case "FILL_BLANK", "FILL_IN_BLANK", "FILL_BLANKS", "BLANK" ->
          InterviewQuestionDTO.TYPE_FILL_BLANK;
      case "SHORT_ANSWER", "SUBJECTIVE", "ANSWER" -> InterviewQuestionDTO.TYPE_SHORT_ANSWER;
      case "READING" -> InterviewQuestionDTO.TYPE_READING;
      case "WRITING", "ESSAY" -> InterviewQuestionDTO.TYPE_WRITING;
      default -> options.size() >= 2
          ? InterviewQuestionDTO.TYPE_SINGLE_CHOICE : InterviewQuestionDTO.TYPE_FILL_BLANK;
    };
  }

  /**
   * 模型经常漏填分值；题干里明确写了「（X分）」就直接取。
   */
  private Integer resolveFullScore(Integer modelScore, String text, String questionType) {
    if (modelScore != null && modelScore > 0) {
      return modelScore;
    }
    if (text != null) {
      Matcher matcher = SCORE_PATTERN.matcher(text);
      if (matcher.find()) {
        try {
          return (int) Math.round(Double.parseDouble(matcher.group(1)));
        } catch (NumberFormatException ignored) {
          // fall through
        }
      }
    }
    return switch (questionType) {
      case InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
           InterviewQuestionDTO.TYPE_MULTI_CHOICE -> 3;
      case InterviewQuestionDTO.TYPE_FILL_BLANK -> 4;
      case InterviewQuestionDTO.TYPE_SHORT_ANSWER,
           InterviewQuestionDTO.TYPE_READING -> 6;
      case InterviewQuestionDTO.TYPE_WRITING -> 20;
      default -> 4;
    };
  }

  private String buildAssetInfo(
      List<PaperAssetEntity> assets,
      Map<String, PaperAssetBinding> bindings
  ) {
    if (assets.isEmpty()) {
      return "（无）";
    }
    StringBuilder sb = new StringBuilder();
    for (PaperAssetEntity asset : assets) {
      sb.append(asset.getAssetKey())
          .append(" page=").append(asset.getPageNo() == null ? "?" : asset.getPageNo())
          .append(" kind=").append(asset.getKind())
          .append(" role=").append(asset.getRole() == null ? "-" : asset.getRole());
      if (asset.getOptionCount() != null) {
        sb.append(" count=").append(asset.getOptionCount());
      }
      if (asset.getAnchorText() != null) {
        sb.append(" anchor=\"").append(asset.getAnchorText()).append('"');
      }
      PaperAssetBinding binding = bindings.get(asset.getAssetKey());
      if (binding != null) {
        sb.append(" line=").append(binding.anchorLine());
      }
      if (asset.getLayout() != null) {
        sb.append(" layout=").append(asset.getLayout());
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  private String canonicalAssetGeometry(List<PageGeometrySource> sources) {
    StringBuilder sb = new StringBuilder();
    sources.stream()
        .flatMap(source -> source.snapshot().assets().stream()
            .map(asset -> Map.entry(source.snapshot().pageNo(), asset)))
        .sorted(Comparator
            .comparing((Map.Entry<Integer, PageAssetGeometry> entry) -> entry.getValue().assetKey())
            .thenComparingInt(Map.Entry::getKey))
        .forEach(entry -> {
          PageAssetGeometry asset = entry.getValue();
          sb.append(asset.assetKey()).append('|').append(entry.getKey()).append('|')
              .append(asset.kind()).append('|').append(asset.role()).append('|')
              .append(rectKey(asset.rect())).append('|').append(asset.layout()).append('|')
              .append(asset.optionCount()).append('\n');
        });
    return sb.toString();
  }

  private String rectKey(double[] rect) {
    if (rect == null || rect.length < 4) {
      return "-";
    }
    return String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f",
        rect[0], rect[1], rect[2], rect[3]);
  }

  private String buildNumbered(List<String> lines) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      sb.append('L').append(String.format("%04d", i + 1)).append(' ')
          .append(lines.get(i)).append('\n');
    }
    return sb.toString();
  }

  private String joinLines(List<String> lines, int start, int end) {
    StringBuilder sb = new StringBuilder();
    for (int i = start; i <= end && i <= lines.size(); i++) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(lines.get(i - 1));
    }
    return sb.toString();
  }

  private Integer[] pagesOf(int startLine, int endLine, List<int[]> pageRanges) {
    int from = -1;
    int to = -1;
    for (int[] range : pageRanges) {
      if (range[2] < startLine || range[1] > endLine) {
        continue;
      }
      if (from < 0) {
        from = range[0];
      }
      to = range[0];
    }
    if (from < 0) {
      return new Integer[] {null, null};
    }
    return new Integer[] {from, to};
  }

  private String firstLine(List<String> lines, int lineNo) {
    return lineNo >= 1 && lineNo <= lines.size() ? lines.get(lineNo - 1) : "题目";
  }

  private String defaultText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      return Integer.toHexString(value.hashCode());
    }
  }

  private String read(ResourceLoader loader, String path) {
    try {
      return loader.getResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("读取 Prompt 失败: " + path, e);
    }
  }

  private static final class NodeBuilder {
    private final String type;
    private final String title;
    private final List<NodeBuilder> children = new ArrayList<>();
    private String id;
    private String content;
    private InterviewQuestionDTO question;
    private List<String> assetIds = List.of();
    private int startLine;
    private int endLine;

    private NodeBuilder(String type, String title) {
      this.type = type;
      this.title = title;
    }

    private NodeBuilder(String type, String title, String id) {
      this.type = type;
      this.title = title;
      this.id = id;
    }
  }

  private record PageGeometrySource(
      PageRecognitionGeometrySnapshot snapshot,
      int pageLineStart
  ) {
  }

  record AssetGenerationScope(
      List<PaperAssetEntity> assets,
      List<String> missingAssetKeys
  ) {
  }
}
