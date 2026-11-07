package interview.guide.modules.quiz.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.common.model.DifficultyLevel;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.quiz.config.QuizProperties;
import interview.guide.modules.quiz.model.GeneratedPaperDTO;
import interview.guide.modules.quiz.model.QuizConfig;
import interview.guide.modules.quiz.model.QuizSubjectSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模拟测验组卷：按学科 + 知识板块 + 难度 + 题量调用模型出题，产出 {@link PaperTree}。
 * <p>
 * 与「试卷切题」的区别：切题是从已有文本抽题，这里是按知识点从零命题，
 * 因此多了一层 sections（每个板块可自带阅读材料）。
 * <p>
 * 稳定性上做了三层约束（对应改造方案里的风险项）：
 * 材料单篇/合计长度上限、题干长度上限、题量上限；
 * 超出部分截断并记 warning，避免把 prompt 撑爆导致单次调用超过 60 秒。
 */
@Slf4j
@Service
public class QuizPaperService {

  private static final String TYPE_SINGLE_CHOICE = InterviewQuestionDTO.TYPE_SINGLE_CHOICE;
  private static final String TYPE_MULTI_CHOICE = InterviewQuestionDTO.TYPE_MULTI_CHOICE;
  private static final String TYPE_TRUE_FALSE = InterviewQuestionDTO.TYPE_TRUE_FALSE;
  private static final String TYPE_FILL_BLANK = InterviewQuestionDTO.TYPE_FILL_BLANK;
  private static final String TYPE_SHORT_ANSWER = InterviewQuestionDTO.TYPE_SHORT_ANSWER;
  private static final String TYPE_READING = InterviewQuestionDTO.TYPE_READING;
  private static final String TYPE_WRITING = InterviewQuestionDTO.TYPE_WRITING;
  private static final String TYPE_CASE_ANALYSIS = InterviewQuestionDTO.TYPE_CASE_ANALYSIS;
  private static final String TYPE_OTHER = InterviewQuestionDTO.TYPE_OTHER;

  private static final Set<String> VALID_TYPES = Set.of(
      TYPE_SINGLE_CHOICE, TYPE_MULTI_CHOICE, TYPE_TRUE_FALSE, TYPE_FILL_BLANK,
      TYPE_SHORT_ANSWER, TYPE_READING, TYPE_WRITING, TYPE_CASE_ANALYSIS, TYPE_OTHER);

  /** 命中任一关键词的板块视为「阅读型」，材料允许更长且截断时整板块丢弃 */
  private static final List<String> READING_KEYWORDS = List.of("阅读", "完形", "文言文");

  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final QuizCatalogService quizCatalogService;
  private final InterviewPersistenceService persistenceService;
  private final QuizProperties properties;
  private final PromptTemplate systemPromptTemplate;
  private final PromptTemplate userPromptTemplate;
  private final BeanOutputConverter<GeneratedPaperDTO> outputConverter =
      new BeanOutputConverter<>(GeneratedPaperDTO.class);

  public QuizPaperService(
      LlmProviderRegistry llmProviderRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      QuizCatalogService quizCatalogService,
      InterviewPersistenceService persistenceService,
      QuizProperties properties,
      ResourceLoader resourceLoader) throws IOException {
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.quizCatalogService = quizCatalogService;
    this.persistenceService = persistenceService;
    this.properties = properties;
    this.systemPromptTemplate = new PromptTemplate(
        resourceLoader.getResource("classpath:prompts/quiz-paper-generation-system.st")
            .getContentAsString(StandardCharsets.UTF_8));
    this.userPromptTemplate = new PromptTemplate(
        resourceLoader.getResource("classpath:prompts/quiz-paper-generation-user.st")
            .getContentAsString(StandardCharsets.UTF_8));
  }

  public record GeneratedQuiz(PaperTree paperTree,
                              List<InterviewQuestionDTO> questions,
                              String subjectLabel,
                              List<String> warnings) {
  }

  /**
   * 组卷主流程。
   */
  public GeneratedQuiz generate(QuizConfig config) {
    QuizSubjectSpec subject = quizCatalogService.resolveSubject(config.skillId());

    int questionCount = clamp(config.questionCount(),
        properties.getMinQuestions(), properties.getMaxQuestions());
    List<QuizSubjectSpec.Category> categories =
        resolveCategories(subject.categories(), config.categories());
    Map<String, Integer> allocation = allocate(categories, questionCount);
    Map<String, Integer> scoreByType = scoreByType(subject.questionTypes());

    List<HistoricalQuestion> history =
        persistenceService.getHistoricalQuestions(config.skillId(), null);

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("examName", subject.examName());
    variables.put("subjectName", subject.name());
    variables.put("persona", personaOf(subject));
    variables.put("outlineSection", buildOutlineSection(subject));
    variables.put("difficultyDescription",
        // 学段无关的中文难度描述；具体深浅由学科资料/知识板块本身的学段决定
        DifficultyLevel.describe(config.difficulty()));
    variables.put("questionCount", questionCount);
    variables.put("typePreference",
        describeTypePreference(config.questionTypes(), subject.questionTypes()));
    variables.put("categoryTable", renderCategoryTable(categories, allocation));
    variables.put("historicalSection", renderHistory(history));
    variables.put("maxMaterialChars", properties.getMaxMaterialCharsPerMaterial());
    variables.put("maxReadingMaterialChars", properties.getMaxReadingMaterialCharsPerMaterial());
    variables.put("maxTotalMaterialChars", properties.getMaxTotalMaterialChars());
    variables.put("maxStemChars", properties.getMaxQuestionStemChars());

    String systemPrompt = systemPromptTemplate.render(variables) + "\n\n" + outputConverter.getFormat();
    String userPrompt = userPromptTemplate.render(variables);

    GeneratedPaperDTO generated;
    try {
      ChatClient chatClient = llmProviderRegistry.getPlainChatClient(config.llmProvider());
      generated = structuredOutputInvoker.invokeWithoutSchemaValidation(
          chatClient,
          systemPrompt,
          userPrompt,
          outputConverter,
          ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "模拟测验组卷失败：",
          "模拟测验组卷",
          log);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("模拟测验组卷模型调用失败: {}", ErrorLogSanitizer.summarize(e),
          ErrorLogSanitizer.forLogging(e));
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "模拟测验组卷失败");
    }

    return toPaperTree(generated, config, subject, scoreByType, questionCount);
  }

  // ==================== 规范化：把模型输出收敛成可信的 PaperTree ====================

  GeneratedQuiz toPaperTree(GeneratedPaperDTO generated, QuizConfig config,
                            QuizSubjectSpec subject, Map<String, Integer> scoreByType,
                            int questionCount) {
    List<String> warnings = new ArrayList<>();
    List<GeneratedPaperDTO.Section> rawSections =
        generated != null && generated.sections() != null ? generated.sections() : List.of();
    if (rawSections.isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "组卷未返回任何题目，请稍后重试");
    }

    List<PaperNode> sectionNodes = new ArrayList<>();
    List<InterviewQuestionDTO> flat = new ArrayList<>();
    int totalMaterialChars = 0;
    int questionIndex = 0;
    int sectionSeq = 0;
    int longReadingSections = 0;

    for (GeneratedPaperDTO.Section section : rawSections) {
      if (section == null || section.questions() == null || section.questions().isEmpty()) {
        continue;
      }
      if (questionIndex >= questionCount) {
        warnings.add("模型返回题量超出上限，已截断到 " + questionCount + " 题");
        break;
      }

      String sectionTitle = firstNonBlank(section.title(),
          section.knowledgeCategory(), subject.name() + "题目");
      String categoryKey = firstNonBlank(section.knowledgeCategory(), section.title(), sectionTitle);
      sectionSeq++;

      List<PaperNode> children = new ArrayList<>();
      int childOrder = 1;

      boolean hasMaterial = section.material() != null && section.material().content() != null
          && !section.material().content().isBlank();
      boolean longReading = hasMaterial && isReadingSection(section, subject);
      if (longReading && longReadingSections >= properties.getMaxLongReadingSectionsPerPaper()) {
        warnings.add("阅读板块「" + sectionTitle + "」超过单卷长材料数量上限，已整板块丢弃");
        continue;
      }

      if (hasMaterial) {
        String rawMaterial = section.material().content().trim();
        int materialCap = longReading
            ? properties.getMaxReadingMaterialCharsPerMaterial()
            : properties.getMaxMaterialCharsPerMaterial();
        String materialContent = truncateMaterial(rawMaterial, materialCap);
        if (materialContent.length() < rawMaterial.length()) {
          if (longReading) {
            // 阅读材料一旦被截断，题目就可能引用材料里不存在的细节，宁缺毋错：整板块丢弃
            warnings.add("阅读板块「" + sectionTitle + "」材料超过 " + materialCap
                + " 字，已整板块丢弃（避免题料不一致），请重试");
            continue;
          }
          warnings.add("材料「" + sectionTitle + "」过长，已截断到 " + materialCap + " 字");
        }
        if (totalMaterialChars + materialContent.length() > properties.getMaxTotalMaterialChars()) {
          int remaining = Math.max(200, properties.getMaxTotalMaterialChars() - totalMaterialChars);
          if (longReading && remaining < materialContent.length()) {
            warnings.add("整卷材料累计超过上限，已丢弃阅读板块「" + sectionTitle + "」");
            continue;
          }
          materialContent = truncateMaterial(materialContent, remaining);
          warnings.add("整卷材料累计超过上限，已压缩「" + sectionTitle + "」");
        }
        totalMaterialChars += materialContent.length();
        if (longReading) {
          longReadingSections++;
        }
        children.add(PaperNode.material("m" + sectionSeq, childOrder++,
            firstNonBlank(section.material().title(), "材料"), materialContent));
      }

      int sectionQuestions = 0;
      for (GeneratedPaperDTO.Question raw : section.questions()) {
        if (questionIndex >= questionCount) {
          break;
        }
        if (sectionQuestions >= properties.getMaxQuestionsPerSection()) {
          warnings.add("板块「" + sectionTitle + "」题目过多，已截断");
          break;
        }
        InterviewQuestionDTO dto =
            toQuestionDTO(raw, questionIndex, categoryKey, sectionTitle, warnings, scoreByType);
        if (dto == null) {
          continue;
        }
        children.add(PaperNode.ofQuestion(childOrder++, dto));
        flat.add(dto);
        questionIndex++;
        sectionQuestions++;
      }

      // 只有材料的板块（例如纯阅读材料但题目全被截断）直接丢弃，避免出现空大题
      if (sectionQuestions == 0) {
        continue;
      }
      sectionNodes.add(PaperNode.section("s" + sectionSeq, sectionSeq, sectionTitle, categoryKey, children));
    }

    if (flat.isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "组卷没有产出可用题目，请稍后重试");
    }

    String title = firstNonBlank(generated.paperTitle(), subject.name() + "模拟测验");
    PaperTree tree = PaperTree.recalculate(new PaperTree(
        PaperTree.CURRENT_VERSION,
        PaperNode.paper("n0", title, showSubjectCategory(subject, config), sectionNodes)));

    log.info("组卷完成: skill={}, 题数={}, 大题数={}, warnings={}",
        config.skillId(), flat.size(), sectionNodes.size(), warnings.size());
    return new GeneratedQuiz(tree, flat, subject.name(), warnings);
  }

  /**
   * 单题规范化：题型兜底、选项清洗补标签、题干与分值兜底。
   *
   * @return 不可用的题目返回 null（调用方跳过）
   */
  private InterviewQuestionDTO toQuestionDTO(GeneratedPaperDTO.Question raw, int questionIndex,
                                             String categoryKey, String categoryLabel,
                                             List<String> warnings,
                                             Map<String, Integer> scoreByType) {
    if (raw == null || raw.stem() == null || raw.stem().isBlank()) {
      return null;
    }
    String stem = truncate(raw.stem().trim(), properties.getMaxQuestionStemChars());

    List<InterviewQuestionDTO.QuestionOption> options = normalizeOptions(raw.options());
    String questionType = normalizeType(raw.questionType(), options, warnings, questionIndex);
    if (!isChoice(questionType)) {
      options = List.of();
    }

    Integer fullScore = raw.fullScore() != null && raw.fullScore() > 0
        ? raw.fullScore() : defaultScore(questionType, scoreByType);

    String referenceAnswer = trimToNull(raw.referenceAnswer());
    if (isChoice(questionType) && referenceAnswer == null) {
      warnings.add("第 " + (questionIndex + 1) + " 题缺少参考答案，已标记为待批改");
    }

    return new InterviewQuestionDTO(
        questionIndex,
        stem,
        categoryKey,
        categoryLabel,
        topicSummary(stem),
        null,
        null,
        null,
        false,
        null,
        referenceAnswer,
        raw.knowledgePoints() == null ? List.of() : raw.knowledgePoints(),
        trimToNull(raw.scoringRubric()),
        null,
        fullScore,
        questionType,
        options.isEmpty() ? null : options,
        trimToNull(raw.analysis()),
        null,
        null,
        null,
        null,
        null,
        false);
  }

  private List<InterviewQuestionDTO.QuestionOption> normalizeOptions(List<GeneratedPaperDTO.Option> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<InterviewQuestionDTO.QuestionOption> result = new ArrayList<>();
    LinkedHashSet<String> seenText = new LinkedHashSet<>();
    for (GeneratedPaperDTO.Option option : raw) {
      if (option == null || option.text() == null || option.text().isBlank()) {
        continue;
      }
      String text = truncate(option.text().trim(), properties.getMaxOptionChars());
      text = text.replaceFirst("^\\s*[A-Da-d][\\.．、:：)]?\\s*", "");
      if (!seenText.add(text)) {
        continue;
      }
      String key = option.key() == null ? "" : option.key().trim().toUpperCase();
      if (key.length() != 1 || key.charAt(0) < 'A' || key.charAt(0) > 'J') {
        key = String.valueOf((char) ('A' + result.size()));
      }
      result.add(new InterviewQuestionDTO.QuestionOption(key, text));
      if (result.size() >= 10) {
        break;
      }
    }
    return result.size() >= 2 ? result : List.of();
  }

  private String normalizeType(String rawType, List<InterviewQuestionDTO.QuestionOption> options,
                               List<String> warnings, int questionIndex) {
    String type = rawType == null ? "" : rawType.trim().toUpperCase();
    boolean hasOptions = options.size() >= 2;
    if (isChoice(type)) {
      if (hasOptions) {
        return type;
      }
      warnings.add("第 " + (questionIndex + 1) + " 题标注为选择题但缺少选项，已降级为简答题");
      return TYPE_SHORT_ANSWER;
    }
    if (VALID_TYPES.contains(type)) {
      return type;
    }
    return hasOptions ? TYPE_SINGLE_CHOICE : TYPE_SHORT_ANSWER;
  }

  private boolean isChoice(String type) {
    return TYPE_SINGLE_CHOICE.equals(type) || TYPE_MULTI_CHOICE.equals(type)
        || TYPE_TRUE_FALSE.equals(type);
  }

  private Map<String, Integer> scoreByType(List<QuizSubjectSpec.QuestionType> types) {
    Map<String, Integer> map = new LinkedHashMap<>();
    if (types == null) {
      return map;
    }
    for (QuizSubjectSpec.QuestionType type : types) {
      if (type.type() != null && type.defaultScore() > 0) {
        map.put(type.type(), type.defaultScore());
      }
    }
    return map;
  }

  private int defaultScore(String questionType, Map<String, Integer> scoreByType) {
    Integer configured = scoreByType.get(questionType);
    if (configured != null && configured > 0) {
      return configured;
    }
    return switch (questionType) {
      case TYPE_SINGLE_CHOICE, TYPE_MULTI_CHOICE -> properties.getDefaultChoiceScore();
      case TYPE_TRUE_FALSE -> properties.getDefaultChoiceScore();
      case TYPE_FILL_BLANK -> properties.getDefaultFillScore();
      case TYPE_READING -> properties.getDefaultReadingScore();
      case TYPE_WRITING -> properties.getDefaultWritingScore();
      case TYPE_SHORT_ANSWER -> properties.getDefaultShortAnswerScore();
      case TYPE_CASE_ANALYSIS -> properties.getDefaultShortAnswerScore();
      default -> properties.getDefaultOtherScore();
    };
  }

  // ==================== 组卷参数渲染 ====================

  private List<QuizSubjectSpec.Category> resolveCategories(
      List<QuizSubjectSpec.Category> all, List<String> requested) {
    if (all == null || all.isEmpty()) {
      return List.of();
    }
    if (requested == null || requested.isEmpty()) {
      return all;
    }
    Set<String> wanted = new LinkedHashSet<>(requested);
    List<QuizSubjectSpec.Category> filtered = all.stream()
        .filter(category -> wanted.contains(category.key()))
        .toList();
    return filtered.isEmpty() ? all : filtered;
  }

  /** 按板块 priority 与数量平均分配题量，余数补给前面的板块 */
  private Map<String, Integer> allocate(List<QuizSubjectSpec.Category> categories, int total) {
    Map<String, Integer> allocation = new LinkedHashMap<>();
    if (categories.isEmpty()) {
      return allocation;
    }
    int base = total / categories.size();
    int remainder = total % categories.size();
    for (int i = 0; i < categories.size(); i++) {
      int count = Math.max(1, base + (i < remainder ? 1 : 0));
      allocation.put(categories.get(i).key(), Math.min(count, properties.getMaxQuestionsPerSection()));
    }
    return allocation;
  }

  private String renderCategoryTable(List<QuizSubjectSpec.Category> categories,
                                     Map<String, Integer> allocation) {
    if (categories.isEmpty()) {
      return "（未指定考点，请覆盖该科目的核心考点）";
    }
    StringBuilder builder = new StringBuilder();
    for (QuizSubjectSpec.Category category : categories) {
      builder.append("| ").append(category.key())
          .append(" | ").append(category.label())
          .append(" | ").append(allocation.getOrDefault(category.key(), 1))
          .append(" |\n");
    }
    return builder.toString().trim();
  }

  private String personaOf(QuizSubjectSpec subject) {
    if (subject.persona() != null && !subject.persona().isBlank()) {
      return subject.persona();
    }
    return "你是「" + subject.examName() + "」中「" + subject.name()
        + "」的命题教师，请按该考试的真实考法与难度出题。";
  }

  private String buildOutlineSection(QuizSubjectSpec subject) {
    StringBuilder builder = new StringBuilder();
    if (subject.outlineText() != null && !subject.outlineText().isBlank()) {
      builder.append(subject.outlineText().trim());
    }
    List<String> labels = subject.categories().stream()
        .map(QuizSubjectSpec.Category::label)
        .filter(label -> label != null && !label.isBlank())
        .toList();
    if (!labels.isEmpty()) {
      if (!builder.isEmpty()) {
        builder.append("\n\n");
      }
      builder.append("本卷覆盖考点：").append(String.join("、", labels)).append("。");
    }
    return builder.isEmpty()
        ? "未提供额外大纲，请按该科目通用考纲命题。"
        : builder.toString();
  }

  private String renderHistory(List<HistoricalQuestion> history) {
    if (history == null || history.isEmpty()) {
      return "（无历史题目）";
    }
    StringBuilder builder = new StringBuilder();
    int limit = Math.min(history.size(), properties.getHistoryQuestionLimit());
    for (int i = 0; i < limit; i++) {
      HistoricalQuestion item = history.get(i);
      String summary = item.topicSummary() != null ? item.topicSummary() : item.question();
      builder.append("- ").append(truncate(summary, 60)).append('\n');
    }
    return builder.toString().trim();
  }

  private String describeTypePreference(List<String> requested,
                                        List<QuizSubjectSpec.QuestionType> subjectTypes) {
    if (requested != null && !requested.isEmpty()) {
      return "优先使用以下题型：" + String.join("、", requested);
    }
    if (subjectTypes == null || subjectTypes.isEmpty()) {
      return "按该科目常见考试题型自动配比（客观题与主观题兼顾）";
    }
    int totalWeight = subjectTypes.stream()
        .mapToInt(type -> Math.max(1, type.weight()))
        .sum();
    List<String> parts = subjectTypes.stream()
        .map(type -> {
          int percent = Math.round(100f * type.weight() / totalWeight);
          return typeLabel(type.type()) + "（默认 " + type.defaultScore() + " 分，约 "
              + percent + "%）";
        })
        .toList();
    return "按题型权重出题：" + String.join("、", parts) + "。请按该比例分配题量。";
  }

  private String typeLabel(String type) {
    return switch (type == null ? "" : type) {
      case TYPE_SINGLE_CHOICE -> "单选";
      case TYPE_MULTI_CHOICE -> "多选";
      case TYPE_TRUE_FALSE -> "判断";
      case TYPE_FILL_BLANK -> "填空";
      case TYPE_SHORT_ANSWER -> "简答";
      case TYPE_READING -> "阅读";
      case TYPE_WRITING -> "写作";
      case TYPE_CASE_ANALYSIS -> "案例分析";
      default -> "其他";
    };
  }

  private String showSubjectCategory(QuizSubjectSpec subject, QuizConfig config) {
    return subject.name() + " · " + DifficultyLevel.labelOf(config.difficulty());
  }

  // ==================== 工具 ====================

  private static int clamp(Integer value, int min, int max) {
    int v = value == null ? min : value;
    return Math.max(min, Math.min(max, v));
  }

  private static String truncate(String text, int max) {
    if (text == null || text.length() <= max) {
      return text;
    }
    return text.substring(0, max) + "……（后文略）";
  }

  /**
   * 材料截断：优先落在段落边界，其次落在句末，避免把材料切断在单词/句子中间。
   */
  static String truncateMaterial(String text, int max) {
    if (text == null || text.length() <= max) {
      return text;
    }
    int cut = max;
    int newline = text.lastIndexOf('\n', cut);
    if (newline >= max / 2) {
      cut = newline;
    } else {
      int sentence = -1;
      for (char punct : new char[]{'。', '！', '？', '.', '!', '?'}) {
        int idx = text.lastIndexOf(punct, cut);
        if (idx > sentence) {
          sentence = idx;
        }
      }
      if (sentence >= max / 2) {
        cut = sentence + 1;
      }
    }
    return text.substring(0, cut).stripTrailing() + "……（后文略）";
  }

  /**
   * 阅读型板块判定：板块考点/标题命中阅读关键词，或板块内存在 READING 题型。
   * <p>
   * 优先用科目配置里的考点中文名兜底（模型可能只回 knowledgeCategory 的 key），
   * 再退回板块标题与题型。
   */
  static boolean isReadingSection(GeneratedPaperDTO.Section section, QuizSubjectSpec subject) {
    if (section == null) {
      return false;
    }
    if (isReadingKeyword(section.knowledgeCategory()) || isReadingKeyword(section.title())) {
      return true;
    }
    if (subject != null && subject.categories() != null && section.knowledgeCategory() != null) {
      for (QuizSubjectSpec.Category category : subject.categories()) {
        if (section.knowledgeCategory().equals(category.key())
            && isReadingKeyword(category.label())) {
          return true;
        }
      }
    }
    return section.questions() != null && section.questions().stream()
        .anyMatch(q -> q != null && TYPE_READING.equalsIgnoreCase(q.questionType()));
  }

  private static boolean isReadingKeyword(String text) {
    if (text == null) {
      return false;
    }
    return READING_KEYWORDS.stream().anyMatch(text::contains);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private static String topicSummary(String stem) {
    String single = stem.replaceAll("\\s+", " ").trim();
    return single.length() <= 20 ? single : single.substring(0, 20);
  }
}
