package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.auth.security.OwnerScope;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.common.model.DifficultyLevel;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.model.QuestionGenerationConfig;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库问题异步生成服务
 * 由 QuestionGenStreamConsumer 调用，负责实际的 LLM 生成和结果持久化。
 * LLM 调用不在事务内；追加新题目与更新完成状态在同一个最小事务中完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseQuestionGenerationService {

  private final OwnerScope ownerScope;

  private static final int RETRIEVAL_TOP_K = 12;
  private static final int RETRIEVAL_QUERY_TOP_K = 4;
  private static final int MAX_CONTEXT_CHARS = 5000;
  /** 提示词里"已有题目"最多列多少条（避免提示词过长；去重不受此限制，用的是全量题目） */
  private static final int MAX_PROMPT_EXISTING_QUESTIONS = 30;

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final KnowledgeBaseQuestionRepository questionRepository;
  private final KnowledgeBaseVectorService vectorService;
  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PromptSanitizer promptSanitizer;
  private final QuestionGenerationStateService stateService;
  private final ObjectMapper objectMapper;

  @Value("classpath:prompts/knowledgebase-question-generation-system.st")
  private Resource systemPromptResource;

  @Value("classpath:prompts/knowledgebase-question-generation-user.st")
  private Resource userPromptResource;

  private final BeanOutputConverter<QuestionListDTO> outputConverter =
      new BeanOutputConverter<>(QuestionListDTO.class);

  record QuestionListDTO(List<QuestionDTO> questions) {}

  record QuestionDTO(
      String category,
      String type,
      String question,
      String topicSummary,
      String referenceAnswer,
      List<String> keyPoints,
      String scoringRubric,
      String questionType,
      List<OptionDTO> options,
      Integer fullScore,
      String analysis,
      List<KnowledgeBaseQuestionFollowUpDTO> followUps
  ) {}

  record OptionDTO(String key, String text) {}

  private record GenerationBatch(
      List<KnowledgeBaseQuestionEntity> questions,
      int skippedCount
  ) {
  }

  /**
   * 执行问题生成（由 Consumer 调用，不在事务中）
   * 流程：校验 → 检索上下文 → 调用 LLM → 替换旧问题并保存新问题（小事务）
   */
  public void executeGeneration(
      Long kbId,
      String taskId,
      QuestionGenerationConfig config
  ) {
    KnowledgeBaseEntity kb = knowledgeBaseRepository
        .findByIdOwned(kbId, ownerScope.effectiveOwnerId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在或无权访问"));

    // 再次确认任务ID匹配
    if (!taskId.equals(kb.getQuestionGenTaskId())) {
      log.info("任务ID不匹配，放弃生成: kbId={}, msgTaskId={}, currentTaskId={}",
          kbId, taskId, kb.getQuestionGenTaskId());
      return;
    }

    String normalizedDifficulty = normalizeDifficulty(config.difficulty());
    int normalizedFollowUp = Math.max(0, Math.min(config.followUpCount(), 5));
    int normalizedCategoryLimit = Math.max(1, Math.min(config.categoryLimit(), 5));

    // 1. 检索上下文（不在事务中）
    String context = buildGenerationContext(kb);

    // 已有题目：既进提示词避免重复出题，也用于落库前的全库去重（出题是追加语义，不覆盖旧题）
    List<String> existingQuestions = questionRepository.findQuestionTextsByKnowledgeBaseId(kbId);
    Set<String> existingKeys = existingQuestions.stream()
        .map(this::normalizeQuestionKey)
        .filter(key -> !key.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));

    // 2. 调用 LLM（不在事务中）
    ChatClient chatClient = llmProviderRegistry.getPlainChatClient(config.llmProvider());
    QuestionListDTO generated = callLlm(kb, chatClient, normalizedDifficulty,
        Math.max(1, config.questionCount()), normalizedFollowUp, normalizedCategoryLimit, context,
        existingQuestions);

    // 3. 校验生成结果
    if (generated == null || generated.questions() == null || generated.questions().isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "知识库题库生成结果为空");
    }

    // 4. 构建实体列表（不在事务中）
    GenerationBatch batch =
        buildEntities(kb, normalizedDifficulty, context, normalizedFollowUp, generated, existingKeys);
    if (batch.questions().isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "知识库题库生成结果无有效题干");
    }

    // 5. 在一个小事务中校验当前任务、追加题目并更新完成状态
    boolean completed = stateService.appendQuestionsAndComplete(
        kbId, taskId, batch.questions(), batch.skippedCount());
    if (!completed) {
      log.info("题目生成任务已被替换，丢弃旧结果: kbId={}, taskId={}", kbId, taskId);
      return;
    }

    log.info("知识库问题异步生成完成: kbId={}, taskId={}, count={}",
        kbId, taskId, batch.questions().size());
  }

  private QuestionListDTO callLlm(
      KnowledgeBaseEntity kb,
      ChatClient chatClient,
      String difficulty,
      int questionCount,
      int followUpCount,
      int categoryLimit,
      String context,
      List<String> existingQuestions
  ) {
    try {
      String systemPrompt = loadTemplate(systemPromptResource).render()
          + "\n\n"
          + outputConverter.getFormat();
      String userPrompt = loadTemplate(userPromptResource)
          .render(Map.of(
              "knowledgeBaseName", promptSanitizer.sanitize(kb.getName()),
              // 给模型的是中文难度描述（简单/中等/偏难 + 认知要求），不是 junior/mid/senior 这种内部编码
              "difficultyDescription", DifficultyLevel.describe(difficulty),
              "questionCount", questionCount,
              "followUpCount", followUpCount,
              "categoryLimit", categoryLimit,
              "existingCategories", promptSanitizer.sanitize(
                  buildExistingCategorySection(kb.getId())),
              "existingQuestions", promptSanitizer.sanitize(
                  buildExistingQuestionSection(existingQuestions)),
              "context", PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
                  + promptSanitizer.wrapWithDelimiters("knowledge-base",
                      promptSanitizer.sanitize(context))
          ));

      return structuredOutputInvoker.invoke(
          chatClient,
          systemPrompt,
          userPrompt,
          outputConverter,
          ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "知识库题库生成失败：",
          "知识库题库生成",
          log
      );
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("知识库题库生成LLM调用失败: kbId={}, error={}", kb.getId(),
          ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "知识库题库生成失败");
    }
  }

  private GenerationBatch buildEntities(
      KnowledgeBaseEntity kb,
      String difficulty,
      String sourceContext,
      int followUpCount,
      QuestionListDTO generated,
      Set<String> existingKeys
  ) {
    List<KnowledgeBaseQuestionEntity> entities = new ArrayList<>();
    Set<String> batchKeys = new LinkedHashSet<>();
    int skippedCount = 0;
    for (QuestionDTO dto : generated.questions()) {
      if (dto == null || dto.question() == null || dto.question().isBlank()) {
        skippedCount += 1;
        continue;
      }
      String rawQuestion = dto.question().trim();
      String key = normalizeQuestionKey(rawQuestion);
      // 本批内重复，或与题库里已有的题重复（追加语义：重复的跳过，不动旧题）
      if (existingKeys.contains(key) || !batchKeys.add(key)) {
        skippedCount += 1;
        continue;
      }
      String category = normalizeCategory(dto.category(), kb.getName());
      KnowledgeBaseQuestionEntity entity = new KnowledgeBaseQuestionEntity();
      entity.setKnowledgeBase(kb);
      entity.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
      entity.setDifficulty(difficulty);
      entity.setType(trimToNull(dto.type()));
      entity.setCategory(category);
      entity.setQuestion(rawQuestion);
      entity.setTopicSummary(trimToNull(dto.topicSummary()));
      entity.setReferenceAnswer(trimToNull(dto.referenceAnswer()));
      entity.setKeyPointsJson(writeStringList(dto.keyPoints()));
      entity.setScoringRubric(trimToNull(dto.scoringRubric()));
      // 考卷题：题型 + 选项 + 分值 + 解析（选择题才有选项）
      String questionType = normalizeQuestionType(dto.questionType(), dto.options());
      entity.setQuestionType(questionType);
      entity.setOptionsJson(writeOptions(isChoiceType(questionType) ? normalizeOptions(dto.options()) : List.of()));
      entity.setFullScore(dto.fullScore() != null && dto.fullScore() > 0
          ? dto.fullScore() : defaultScoreFor(questionType));
      entity.setAnalysis(trimToNull(dto.analysis()));
      // 测验不做追问：即使模型返回了也忽略，老数据仍可读取
      entity.setFollowUpsJson(writeFollowUps(List.of(), 0));
      entity.setSourceContext(sourceContext);
      entity.setKbContentHash(kb.getFileHash());
      entity.setStatus(KnowledgeBaseQuestionStatus.DRAFT);
      entities.add(entity);
    }
    return new GenerationBatch(entities, skippedCount);
  }

  private String buildGenerationContext(KnowledgeBaseEntity kb) {
    List<Document> docs = new ArrayList<>();
    Set<String> seenTexts = new LinkedHashSet<>();
    for (String query : buildGenerationQueries()) {
      List<Document> hits = vectorService.similaritySearch(
          query, List.of(kb.getId()), RETRIEVAL_QUERY_TOP_K, 0);
      for (Document doc : hits) {
        String text = doc.getText();
        if (text == null || text.isBlank() || !seenTexts.add(text.trim())) {
          continue;
        }
        docs.add(doc);
        if (docs.size() >= RETRIEVAL_TOP_K) {
          break;
        }
      }
      if (docs.size() >= RETRIEVAL_TOP_K) {
        break;
      }
    }
    if (docs.isEmpty()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
          "知识库未检索到可用于生成题目的内容");
    }
    String context = docs.stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n\n---\n\n"));
    return context.length() <= MAX_CONTEXT_CHARS
        ? context
        : context.substring(0, MAX_CONTEXT_CHARS) + "\n...(知识库片段过长，已截断)";
  }

  private List<String> buildGenerationQueries() {
    return List.of(
        "核心概念 定义 背景 原理",
        "关键流程 步骤 方法 工作机制",
        "规则约束 条件 边界 例外 限制",
        "典型案例 常见问题 应用场景 最佳实践"
    );
  }

  private String buildExistingCategorySection(Long knowledgeBaseId) {
    List<CategoryCount> categories = questionRepository.findCategoryCounts(knowledgeBaseId);
    if (categories.isEmpty()) {
      return "暂无已有方向";
    }
    return categories.stream()
        .limit(10)
        .map(c -> "- " + c.getCategory() + "（" + c.getCount() + " 题）")
        .collect(Collectors.joining("\n"));
  }

  /**
   * 已有题目清单（最新在前，限 {@link #MAX_PROMPT_EXISTING_QUESTIONS} 条）。
   * 不限难度：题库存量是多次生成的累积，跨难度也要避免出重复题。
   */
  private String buildExistingQuestionSection(List<String> existingQuestions) {
    List<String> questions = existingQuestions.stream()
        .filter(question -> question != null && !question.isBlank())
        .limit(MAX_PROMPT_EXISTING_QUESTIONS)
        .map(question -> "- " + question.trim())
        .toList();
    return questions.isEmpty() ? "暂无已有题目" : String.join("\n", questions);
  }

  private PromptTemplate loadTemplate(Resource resource) throws IOException {
    try (var input = resource.getInputStream()) {
      String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      return new PromptTemplate(content);
    }
  }

  private String normalizeDifficulty(String difficulty) {
    // 接受 junior/mid/senior、easy/medium/hard 与"简单/中等/偏难"，统一落成库里的编码
    return DifficultyLevel.normalize(difficulty);
  }

  private String normalizeCategory(String category, String fallback) {
    if (category == null || category.isBlank()) {
      return fallback != null && !fallback.isBlank() ? fallback.trim() : "未分类";
    }
    return category.trim();
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private String normalizeQuestionKey(String question) {
    if (question == null) {
      return "";
    }
    String normalized = Normalizer.normalize(question, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder(normalized.length());
    for (int i = 0; i < normalized.length(); i += 1) {
      char ch = normalized.charAt(i);
      if (Character.isLetterOrDigit(ch)) {
        sb.append(ch);
      }
    }
    return sb.toString();
  }

  private String writeStringList(List<String> values) {
    try {
      List<String> sanitized = values == null ? List.of() : values.stream()
          .filter(value -> value != null && !value.isBlank())
          .map(String::trim)
          .toList();
      return objectMapper.writeValueAsString(sanitized);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目列表字段失败", e);
    }
  }

  // ==================== 考卷题规范化（与模拟测验对齐） ====================

  private static final Set<String> VALID_QUESTION_TYPES = Set.of(
      "SINGLE_CHOICE", "MULTI_CHOICE", "FILL_BLANK", "SHORT_ANSWER",
      "READING", "WRITING", "OTHER");

  /** 题型兜底：非法值按「有选项 → 单选，无选项 → 简答」处理 */
  private String normalizeQuestionType(String rawType, List<OptionDTO> options) {
    String type = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
    boolean hasOptions = normalizeOptions(options).size() >= 2;
    if (isChoiceType(type)) {
      return hasOptions ? type : "SHORT_ANSWER";
    }
    return VALID_QUESTION_TYPES.contains(type)
        ? type
        : (hasOptions ? "SINGLE_CHOICE" : "SHORT_ANSWER");
  }

  private boolean isChoiceType(String type) {
    return "SINGLE_CHOICE".equals(type) || "MULTI_CHOICE".equals(type);
  }

  /** 选项清洗：去标签、去空、去重、按 A/B/C/D 补 key，最多 10 项；不足 2 项视为无选项 */
  private List<OptionDTO> normalizeOptions(List<OptionDTO> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<OptionDTO> result = new ArrayList<>();
    Set<String> seenTexts = new LinkedHashSet<>();
    for (OptionDTO option : raw) {
      if (option == null || option.text() == null || option.text().isBlank()) {
        continue;
      }
      String text = option.text().trim().replaceFirst("^\\s*[A-Da-d][\\.．、:：)]?\\s*", "");
      if (!seenTexts.add(text)) {
        continue;
      }
      String key = option.key() == null ? "" : option.key().trim().toUpperCase(Locale.ROOT);
      if (key.length() != 1 || key.charAt(0) < 'A' || key.charAt(0) > 'J') {
        key = String.valueOf((char) ('A' + result.size()));
      }
      result.add(new OptionDTO(key, text));
      if (result.size() >= 10) {
        break;
      }
    }
    return result.size() >= 2 ? result : List.of();
  }

  private String writeOptions(List<OptionDTO> options) {
    try {
      return options == null || options.isEmpty() ? null : objectMapper.writeValueAsString(options);
    } catch (Exception e) {
      log.warn("选项序列化失败，按无选项处理: {}", e.toString());
      return null;
    }
  }

  private int defaultScoreFor(String questionType) {
    return switch (questionType) {
      case "SINGLE_CHOICE", "MULTI_CHOICE", "FILL_BLANK" -> 3;
      case "READING" -> 8;
      case "WRITING" -> 20;
      case "SHORT_ANSWER" -> 6;
      default -> 5;
    };
  }

  private String writeFollowUps(
      List<KnowledgeBaseQuestionFollowUpDTO> values,
      int followUpCount
  ) {
    try {
      List<KnowledgeBaseQuestionFollowUpDTO> sanitized = values == null ? List.of() : values.stream()
          .filter(value -> value != null && value.question() != null && !value.question().isBlank())
          .map(value -> new KnowledgeBaseQuestionFollowUpDTO(
              value.question().trim(),
              trimToNull(value.referenceAnswer()),
              value.keyPoints() == null ? List.of() : value.keyPoints().stream()
                  .filter(item -> item != null && !item.isBlank())
                  .map(String::trim)
                  .toList(),
              trimToNull(value.scoringRubric())
          ))
          .limit(followUpCount)
          .toList();
      return objectMapper.writeValueAsString(sanitized);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化追问字段失败", e);
    }
  }
}
