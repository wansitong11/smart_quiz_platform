package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;
import interview.guide.modules.resume.service.BlockTreeDTO.BlockOptionDTO;
import lombok.extern.slf4j.Slf4j;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 试卷结构化：模型只标块类型与行号，后端按行切片建树。
 */
@Slf4j
@Service
public class PaperStructureService {

  private static final Set<String> SUBJECTS = Set.of("chinese", "math", "english");
  private static final Pattern SCORE_PATTERN =
      Pattern.compile("[（(]?\\s*(\\d+(?:\\.\\d+)?)\\s*分\\s*[）)]?");

  private final ResumeRepository resumeRepository;
  private final PaperPageRepository pageRepository;
  private final PaperAssetService assetService;
  private final OptionRowSplitter optionRowSplitter;
  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PaperQuestionExtractionService legacyExtractionService;
  private final TransactionalExecutor transactionalExecutor;
  private final ObjectMapper objectMapper;
  private final PromptTemplate systemPromptTemplate;
  private final PromptTemplate userPromptTemplate;
  private final BeanOutputConverter<BlockTreeDTO> outputConverter;

  public PaperStructureService(
      ResumeRepository resumeRepository,
      PaperPageRepository pageRepository,
      PaperAssetService assetService,
      OptionRowSplitter optionRowSplitter,
      LlmProviderRegistry llmProviderRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      PaperQuestionExtractionService legacyExtractionService,
      TransactionalExecutor transactionalExecutor,
      ObjectMapper objectMapper,
      ResourceLoader resourceLoader
  ) {
    this.resumeRepository = resumeRepository;
    this.pageRepository = pageRepository;
    this.assetService = assetService;
    this.optionRowSplitter = optionRowSplitter;
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.legacyExtractionService = legacyExtractionService;
    this.transactionalExecutor = transactionalExecutor;
    this.objectMapper = objectMapper;
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
  }

  /**
   * 结构预览：有缓存且文本未变时直接复用。
   */
  public BuiltStructure build(Long paperId) {
    PracticePaperEntity paper = resumeRepository.findById(paperId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_NOT_FOUND, "试卷不存在"));
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId).stream()
        .filter(page -> page.getStatus() == PageStatus.READY)
        .toList();
    if (pages.isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_PARSE_FAILED, "没有可切题的页");
    }

    List<String> lines = new ArrayList<>();
    List<int[]> pageRanges = new ArrayList<>();
    int lineNo = 0;
    for (PaperPageEntity page : pages) {
      int start = lineNo + 1;
      String text = page.getTextContent();
      if (text != null) {
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
          if (!line.isBlank()) {
            lineNo++;
            lines.add(line.stripTrailing());
          }
        }
      }
      pageRanges.add(new int[] {page.getPageNo(), start, lineNo});
    }
    if (lines.isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_PARSE_FAILED, "各页文本为空，无法切题");
    }

    String numbered = buildNumbered(lines);
    String hash = sha256(numbered);
    List<PaperAssetEntity> assets = resolveAnchors(assetService.list(paperId), lines);
    BlockTreeDTO blocks = loadCached(paper, hash);
    if (blocks == null) {
      blocks = callModelChunked(lines, pageRanges, assets);
      storeCache(paperId, hash, blocks);
    }
    return assemble(paperId, lines, pageRanges, blocks, assets);
  }

  private BlockTreeDTO loadCached(PracticePaperEntity paper, String hash) {
    if (paper.getStructureJson() == null || !hash.equals(paper.getStructureHash())) {
      return null;
    }
    try {
      return objectMapper.readValue(paper.getStructureJson(), BlockTreeDTO.class);
    } catch (JacksonException e) {
      log.warn("结构缓存解析失败，将重新调用模型: paperId={}", paper.getId());
      return null;
    }
  }

  private void storeCache(Long paperId, String hash, BlockTreeDTO blocks) {
    String json;
    try {
      json = objectMapper.writeValueAsString(blocks);
    } catch (JacksonException e) {
      log.warn("结构结果序列化失败，跳过缓存: paperId={}", paperId);
      return;
    }
    transactionalExecutor.run(() -> resumeRepository.findById(paperId).ifPresent(paper -> {
      paper.setStructureJson(json);
      paper.setStructureHash(hash);
      resumeRepository.save(paper);
    }));
  }

  private BlockTreeDTO callModel(String numberedText, String assetInfo) {
    String system = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
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
        log
    );
    if (blocks == null || blocks.nodes() == null || blocks.nodes().isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_NO_QUESTION_FOUND, "模型没有返回结构节点");
    }
    return blocks;
  }

  /**
   * 分块调用：整卷一次输出 40~50 题的 JSON 很容易被截断，
   * 按"每块约 2~3 页 / 2800 字"调用，行号保持全卷连续，最后合并节点。
   */
  private BlockTreeDTO callModelChunked(List<String> lines, List<int[]> pageRanges,
                                        List<PaperAssetEntity> assets) {
    List<BlockNodeDTO> allNodes = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    String subjectKey = null;
    int chunkStart = -1;
    int chunkEnd = -1;
    int charCount = 0;
    int chunkCount = 0;

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
      if (chunkStart >= 0 && charCount + pageChars > 2800) {
        ChunkResult result = callChunk(lines, chunkStart, chunkEnd, assets);
        allNodes.addAll(result.nodes());
        warnings.addAll(result.warnings());
        if (subjectKey == null) {
          subjectKey = result.subjectKey();
        }
        if (result.success()) {
          chunkCount++;
        }
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
      ChunkResult result = callChunk(lines, chunkStart, chunkEnd, assets);
      allNodes.addAll(result.nodes());
      warnings.addAll(result.warnings());
      if (subjectKey == null) {
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
    return new BlockTreeDTO(subjectKey, null, allNodes, warnings);
  }

  private ChunkResult callChunk(List<String> lines, int start, int end,
                                List<PaperAssetEntity> assets) {
    try {
      BlockTreeDTO part = callModel(numberedRange(lines, start, end), buildAssetInfo(assets));
      return new ChunkResult(
          part.nodes() == null ? List.of() : part.nodes(),
          part.warnings() == null ? List.of() : part.warnings(),
          part.subjectKey(),
          true
      );
    } catch (Exception e) {
      log.warn("结构分块失败，跳过: lines={}-{}", start, end, e);
      return new ChunkResult(List.of(),
          List.of("第 " + start + "–" + end + " 行结构化失败，已跳过，请在校对页补录"), null, false);
    }
  }

  private record ChunkResult(List<BlockNodeDTO> nodes, List<String> warnings,
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

  private BuiltStructure assemble(Long paperId, List<String> lines, List<int[]> pageRanges,
                                  BlockTreeDTO blocks, List<PaperAssetEntity> allAssets) {
    List<BlockNodeDTO> nodes = new ArrayList<>(blocks.nodes());
    nodes.sort((a, b) -> Integer.compare(a.startLine(), b.startLine()));
    Map<String, PaperAssetEntity> assetMap = new LinkedHashMap<>();
    allAssets.forEach(asset -> assetMap.put(asset.getAssetKey(), asset));

    List<String> warnings = new ArrayList<>();
    if (blocks.warnings() != null) {
      warnings.addAll(blocks.warnings());
    }
    boolean[] covered = new boolean[lines.size() + 1];

    NodeBuilder root = new NodeBuilder("PAPER", "试卷");
    NodeBuilder currentPart = null;
    NodeBuilder currentSection = null;
    NodeBuilder currentMaterial = null;
    List<QuestionEntry> questionEntries = new ArrayList<>();
    int questionIndex = 0;
    boolean afterAnswerKey = false;

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
      // 卷末答案之后的内容一律不进入题目树（模型偶尔会把答案示例又标成题目）
      if (afterAnswerKey) {
        continue;
      }
      if ("ANSWER_KEY".equals(type)) {
        afterAnswerKey = true;
        continue;
      }
      String text = joinLines(lines, start, end);
      switch (type) {
        case "PART" -> {
          NodeBuilder part = new NodeBuilder("SECTION", defaultText(node.title(), firstLine(lines, start)));
          part.startLine = start;
          root.children.add(part);
          currentPart = part;
          currentSection = null;
          currentMaterial = null;
        }
        case "SECTION" -> {
          NodeBuilder section = new NodeBuilder("SECTION",
              defaultText(node.title(), firstLine(lines, start)));
          section.startLine = start;
          (currentPart != null ? currentPart : root).children.add(section);
          currentSection = section;
          currentMaterial = null;
        }
        case "MATERIAL" -> {
          NodeBuilder material = new NodeBuilder("MATERIAL", defaultText(node.title(), "材料"));
          material.startLine = start;
          material.content = text;
          material.assetIds = filterAssets(node.assetRefs(), assetMap, start, end,
              pageOfLine(start, pageRanges), true);
          NodeBuilder parent = currentSection != null ? currentSection
              : currentPart != null ? currentPart : root;
          parent.children.add(material);
          currentMaterial = material;
        }
        case "INSTRUCTION" -> {
          NodeBuilder instruction = new NodeBuilder("INSTRUCTION", "说明");
          instruction.content = text;
          (currentSection != null ? currentSection : root).children.add(instruction);
        }
        case "QUESTION", "SUB_QUESTION", "BLANK" -> {
          InterviewQuestionDTO dto = toQuestion(questionIndex++, node, text, start, end, pageRanges);
          NodeBuilder question = new NodeBuilder("QUESTION", null);
          question.startLine = start;
          question.question = dto;
          question.assetIds = filterAssets(node.assetRefs(), assetMap, start, end,
              pageOfLine(start, pageRanges), false);
          NodeBuilder parent = currentMaterial != null ? currentMaterial
              : currentSection != null ? currentSection
              : currentPart != null ? currentPart : root;
          parent.children.add(question);
          questionEntries.add(new QuestionEntry(question, node));
        }
        default -> log.debug("忽略未知节点类型: {}", type);
      }
    }

    List<InterviewQuestionDTO> extraQuestions = new ArrayList<>();
    ensureTaskQuestions(root, new int[] {questionIndex}, extraQuestions, warnings);
    if (questionEntries.isEmpty() && extraQuestions.isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_NO_QUESTION_FOUND, "结构模型没有识别到题目");
    }

    // 图形选项：模型没引用完的 OPTION_ROW，按题目顺序补挂
    Set<String> referenced = new HashSet<>();
    for (QuestionEntry entry : questionEntries) {
      if (entry.block.assetRefs() != null) {
        referenced.addAll(entry.block.assetRefs());
      }
    }
    List<PaperAssetEntity> unusedRows = allAssets.stream()
        .filter(asset -> "OPTION_ROW".equals(asset.getKind()))
        .filter(asset -> !referenced.contains(asset.getAssetKey()))
        .toList();
    Map<Integer, String> fallbackRows = new HashMap<>();
    int rowCursor = 0;
    for (QuestionEntry entry : questionEntries) {
      List<String> refs = entry.block.assetRefs() == null ? List.of() : entry.block.assetRefs();
      boolean noTextOptions = entry.block.options() == null || entry.block.options().size() < 2;
      boolean choice = isChoiceType(entry.question.question.questionType());
      if (refs.isEmpty() && choice && noTextOptions && rowCursor < unusedRows.size()) {
        fallbackRows.put(entry.question.question.questionIndex(),
            unusedRows.get(rowCursor++).getAssetKey());
      }
    }

    // 图形选项：切分并回填 assetId
    for (QuestionEntry entry : questionEntries) {
      List<String> refs = entry.block.assetRefs() == null ? List.of() : entry.block.assetRefs();
      if (refs.isEmpty()) {
        String fallback = fallbackRows.get(entry.question.question.questionIndex());
        if (fallback != null) {
          refs = List.of(fallback);
        }
      }
      boolean noTextOptions = entry.block.options() == null || entry.block.options().size() < 2;
      if ("IMAGE".equalsIgnoreCase(entry.block.optionType())
          || (isChoiceType(entry.question.question.questionType()) && noTextOptions)) {
        List<QuestionOption> imageOptions = resolveImageOptions(
            paperId, refs, entry.question.question.options());
        if (imageOptions != null) {
          entry.question.question = entry.question.question.withOptions(imageOptions);
        }
      }
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
    PaperTree tree = PaperTree.recalculate(new PaperTree(PaperTree.CURRENT_VERSION, paperRoot));

    List<InterviewQuestionDTO> questions = new ArrayList<>(questionEntries.stream()
        .map(entry -> entry.question.question)
        .toList());
    questions.addAll(extraQuestions);
    int totalScore = questions.stream()
        .mapToInt(question -> question.fullScore() == null ? 0 : question.fullScore())
        .sum();
    String subject = blocks.subjectKey() != null && SUBJECTS.contains(blocks.subjectKey())
        ? blocks.subjectKey() : InterviewDefaults.SKILL_ID;
    return new BuiltStructure(subject, tree, questions, totalScore, warnings);
  }

  private InterviewQuestionDTO toQuestion(int index, BlockNodeDTO node, String text,
                                          int startLine, int endLine, List<int[]> pageRanges) {
    List<QuestionOption> options = new ArrayList<>();
    if (node.options() != null) {
      for (BlockOptionDTO option : node.options()) {
        if (option != null && option.key() != null) {
          options.add(new QuestionOption(option.key(), option.text()));
        }
      }
    }
    String questionType = normalizeQuestionType(node.questionType(), text, options);
    String category = node.category() == null || node.category().isBlank()
        ? "未分类" : node.category();
    Integer[] pages = pagesOf(startLine, endLine, pageRanges);
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
        defaultText(node.referenceAnswer(), "暂缺"),
        node.keyPoints() == null ? List.of() : node.keyPoints(),
        node.scoringRubric(),
        null,
        resolveFullScore(node.fullScore(), text),
        questionType,
        options.isEmpty() ? null : options,
        node.analysis(),
        pages[0],
        pages[1]
    );
  }

  private List<QuestionOption> resolveImageOptions(Long paperId, List<String> refs,
                                                   List<QuestionOption> existing) {
    if (refs == null || refs.isEmpty()) {
      return null;
    }
    List<PaperAssetEntity> assets = new ArrayList<>();
    for (String ref : refs) {
      assetService.find(paperId, ref).ifPresent(assets::add);
    }
    PaperAssetEntity row = assets.stream()
        .filter(asset -> "OPTION_ROW".equals(asset.getKind()))
        .findFirst().orElse(null);
    int count = existing != null && existing.size() >= 2 ? existing.size()
        : row != null && row.getOptionCount() != null ? row.getOptionCount() : 4;
    if (row != null) {
      try {
        byte[] bytes = assetService.download(row);
        OptionRowSplitter.SplitResult split = optionRowSplitter.split(bytes, count);
        if (split != null) {
          List<QuestionOption> options = new ArrayList<>();
          for (int i = 0; i < split.tiles().size(); i++) {
            String label = String.valueOf((char) ('A' + i));
            String key = row.getAssetKey() + ":" + label;
            if (assetService.find(paperId, key).isEmpty()) {
              assetService.saveTile(paperId, row.getPageNo() == null ? 1 : row.getPageNo(), key,
                  row.getAssetKey(), split.tiles().get(i), 0, 0);
            }
            options.add(new QuestionOption(label, null, key));
          }
          assetService.updateCutOffsets(row, split.cutOffsets().toString());
          return options;
        }
      } catch (Exception e) {
        log.warn("选项带切分失败，降级为整条图: paperId={}, asset={}", paperId, row.getAssetKey());
      }
      return null;
    }

    List<PaperAssetEntity> items = assets.stream()
        .filter(asset -> "OPTION_ITEM".equals(asset.getKind()))
        .toList();
    if (items.size() >= 2) {
      List<QuestionOption> options = new ArrayList<>();
      for (int i = 0; i < items.size(); i++) {
        String label = String.valueOf((char) ('A' + i));
        options.add(new QuestionOption(label, null, items.get(i).getAssetKey()));
      }
      return options;
    }
    return null;
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
    String id = "n" + (++seq[0]);
    if ("QUESTION".equals(builder.type) && builder.question != null) {
      InterviewQuestionDTO question = builder.question;
      return new PaperNode("q" + (question.questionIndex() + 1), PaperNode.TYPE_QUESTION, order,
          0, children.isEmpty(), null, null, null,
          new PaperNode.PaperMeta(question.fullScore(), 1, null, builder.assetIds),
          question.questionIndex(), question, children);
    }
    if ("MATERIAL".equals(builder.type)) {
      return new PaperNode(id, PaperNode.TYPE_MATERIAL, order, 0, children.isEmpty(),
          builder.title, null, builder.content,
          new PaperNode.PaperMeta(null, null, null, builder.assetIds),
          null, null, children);
    }
    if ("INSTRUCTION".equals(builder.type)) {
      return new PaperNode(id, PaperNode.TYPE_INSTRUCTION, order, 0, true,
          null, null, builder.content, null, null, null, List.of());
    }
    return new PaperNode(id, PaperNode.TYPE_SECTION, order, 0, false,
        builder.title, null, null, new PaperNode.PaperMeta(null, null, null),
        null, null, children);
  }

  private boolean isChoiceType(String questionType) {
    return InterviewQuestionDTO.TYPE_SINGLE_CHOICE.equals(questionType)
        || InterviewQuestionDTO.TYPE_MULTI_CHOICE.equals(questionType);
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
              resolveFullScore(null, title),
              normalizeQuestionType(null, title + "\n" + material.content, List.of()),
              null,
              null,
              null,
              null
          );
          NodeBuilder question = new NodeBuilder("QUESTION", null);
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
  private Integer resolveFullScore(Integer modelScore, String text) {
    if (modelScore != null && modelScore > 0) {
      return modelScore;
    }
    if (text == null) {
      return modelScore;
    }
    Matcher matcher = SCORE_PATTERN.matcher(text);
    if (matcher.find()) {
      try {
        return (int) Math.round(Double.parseDouble(matcher.group(1)));
      } catch (NumberFormatException ignored) {
        return modelScore;
      }
    }
    return modelScore;
  }

  private List<PaperAssetEntity> resolveAnchors(List<PaperAssetEntity> assets, List<String> lines) {
    for (PaperAssetEntity asset : assets) {
      if (asset.getAnchorLine() != null || asset.getAnchorText() == null) {
        continue;
      }
      String anchor = asset.getAnchorText().replaceAll("\\s+", "");
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i).replaceAll("\\s+", "").contains(anchor)
            || anchor.contains(lines.get(i).replaceAll("\\s+", ""))) {
          asset.setAnchorLine(i + 1);
          break;
        }
      }
    }
    return assets;
  }

  private String buildAssetInfo(List<PaperAssetEntity> assets) {
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
      sb.append('\n');
    }
    return sb.toString();
  }

  /**
   * 只把题干图/表格挂到节点；选项图由 option.assetId 单独处理。
   * 有锚点行时要求落在节点的行范围内，避免把第 5 页的卡片挂到第 3 页的题上。
   */
  private List<String> filterAssets(List<String> refs, Map<String, PaperAssetEntity> assetMap,
                                    int startLine, int endLine, Integer pageNo,
                                    boolean allowPageFallback) {
    List<String> result = new ArrayList<>();
    if (refs != null) {
      for (String ref : refs) {
        PaperAssetEntity asset = assetMap.get(ref);
        if (asset == null) {
          continue;
        }
        if ("OPTION_ROW".equals(asset.getKind()) || "OPTION_ITEM".equals(asset.getKind())) {
          continue;
        }
        Integer anchor = asset.getAnchorLine();
        if (anchor != null && (anchor < startLine - 3 || anchor > endLine + 3)) {
          continue;
        }
        result.add(ref);
      }
    }
    // 模型经常漏写 assetRefs：同页的 TABLE / FIGURE 按页兜底挂载
    if (allowPageFallback && pageNo != null) {
      for (PaperAssetEntity asset : assetMap.values()) {
        if (result.contains(asset.getAssetKey())) {
          continue;
        }
        if (!"TABLE".equals(asset.getKind()) && !"FIGURE".equals(asset.getKind())) {
          continue;
        }
        if (!pageNo.equals(asset.getPageNo())) {
          continue;
        }
        Integer anchor = asset.getAnchorLine();
        if (anchor == null || (anchor >= startLine - 2 && anchor <= endLine + 2)) {
          result.add(asset.getAssetKey());
        }
      }
    }
    return result;
  }

  private Integer pageOfLine(int lineNo, List<int[]> pageRanges) {
    for (int[] range : pageRanges) {
      if (lineNo >= range[1] && lineNo <= range[2]) {
        return range[0];
      }
    }
    return null;
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

  /**
   * 旧链路回退：结构模型不可用时，用行首题号 + 标题正则。
   */
  public PaperExtractionResult legacyFallback(List<PaperPageEntity> pages) {
    String text = pages.stream()
        .map(PaperPageEntity::getTextContent)
        .filter(value -> value != null && !value.isBlank())
        .reduce((a, b) -> a + "\n" + b)
        .orElse("");
    return legacyExtractionService.extract(text, null);
  }

  private static final class NodeBuilder {
    private final String type;
    private final String title;
    private final List<NodeBuilder> children = new ArrayList<>();
    private String content;
    private InterviewQuestionDTO question;
    private List<String> assetIds = List.of();
    private int startLine;

    private NodeBuilder(String type, String title) {
      this.type = type;
      this.title = title;
    }
  }

  private record QuestionEntry(NodeBuilder question, BlockNodeDTO block) {
  }
}
