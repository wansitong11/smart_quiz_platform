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
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.quiz.config.QuizProperties;
import interview.guide.modules.quiz.model.GeneratedPaperDTO;
import interview.guide.modules.quiz.model.QuizConfig;
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
  private static final String TYPE_FILL_BLANK = InterviewQuestionDTO.TYPE_FILL_BLANK;
  private static final String TYPE_SHORT_ANSWER = InterviewQuestionDTO.TYPE_SHORT_ANSWER;
  private static final String TYPE_READING = InterviewQuestionDTO.TYPE_READING;
  private static final String TYPE_WRITING = InterviewQuestionDTO.TYPE_WRITING;
  private static final String TYPE_OTHER = InterviewQuestionDTO.TYPE_OTHER;

  private static final Set<String> VALID_TYPES = Set.of(
      TYPE_SINGLE_CHOICE, TYPE_MULTI_CHOICE, TYPE_FILL_BLANK, TYPE_SHORT_ANSWER,
      TYPE_READING, TYPE_WRITING, TYPE_OTHER);

  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final InterviewSkillService skillService;
  private final InterviewPersistenceService persistenceService;
  private final QuizProperties properties;
  private final PromptTemplate systemPromptTemplate;
  private final PromptTemplate userPromptTemplate;
  private final BeanOutputConverter<GeneratedPaperDTO> outputConverter =
      new BeanOutputConverter<>(GeneratedPaperDTO.class);

  public QuizPaperService(
      LlmProviderRegistry llmProviderRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      InterviewSkillService skillService,
      InterviewPersistenceService persistenceService,
      QuizProperties properties,
      ResourceLoader resourceLoader) throws IOException {
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.skillService = skillService;
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
    SkillDTO skill = skillService.getSkill(config.skillId());
    if (skill == null) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT,
          "学科不存在：" + config.skillId());
    }

    int questionCount = clamp(config.questionCount(),
        properties.getMinQuestions(), properties.getMaxQuestions());
    List<SkillCategoryDTO> categories = resolveCategories(skill, config.categories());
    Map<String, Integer> allocation = allocate(categories, questionCount);

    List<HistoricalQuestion> history =
        persistenceService.getHistoricalQuestions(config.skillId(), null);

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("subjectName", skill.name());
    variables.put("persona", skill.persona() != null ? skill.persona() : "");
    variables.put("difficultyDescription",
        // 学段无关的中文难度描述；具体深浅由学科资料/知识板块本身的学段决定
        DifficultyLevel.describe(config.difficulty()));
    variables.put("questionCount", questionCount);
    variables.put("typePreference", describeTypePreference(config.questionTypes()));
    variables.put("categoryTable", renderCategoryTable(categories, allocation));
    variables.put("historicalSection", renderHistory(history));
    variables.put("maxMaterialChars", properties.getMaxMaterialCharsPerMaterial());
    variables.put("maxTotalMaterialChars", properties.getMaxTotalMaterialChars());
    variables.put("maxStemChars", properties.getMaxQuestionStemChars());

    String systemPrompt = systemPromptTemplate.render(variables) + "\n\n" + outputConverter.getFormat();
    String userPrompt = userPromptTemplate.render(variables);

    GeneratedPaperDTO generated;
    try {
      ChatClient chatClient = llmProviderRegistry.getPlainChatClient(config.llmProvider());
      generated = structuredOutputInvoker.invoke(
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

    return toPaperTree(generated, config, skill, questionCount);
  }

  // ==================== 规范化：把模型输出收敛成可信的 PaperTree ====================

  private GeneratedQuiz toPaperTree(GeneratedPaperDTO generated, QuizConfig config,
                                    SkillDTO skill, int questionCount) {
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

    for (GeneratedPaperDTO.Section section : rawSections) {
      if (section == null || section.questions() == null || section.questions().isEmpty()) {
        continue;
      }
      if (questionIndex >= questionCount) {
        warnings.add("模型返回题量超出上限，已截断到 " + questionCount + " 题");
        break;
      }

      String sectionTitle = firstNonBlank(section.title(),
          section.knowledgeCategory(), skill.name() + "题目");
      String categoryKey = firstNonBlank(section.knowledgeCategory(), section.title(), sectionTitle);
      sectionSeq++;

      List<PaperNode> children = new ArrayList<>();
      int childOrder = 1;

      if (section.material() != null && section.material().content() != null
          && !section.material().content().isBlank()) {
        String materialContent = truncate(section.material().content().trim(),
            properties.getMaxMaterialCharsPerMaterial());
        if (materialContent.length() < section.material().content().trim().length()) {
          warnings.add("材料「" + sectionTitle + "」过长，已截断到 "
              + properties.getMaxMaterialCharsPerMaterial() + " 字");
        }
        if (totalMaterialChars + materialContent.length() > properties.getMaxTotalMaterialChars()) {
          materialContent = truncate(materialContent,
              Math.max(200, properties.getMaxTotalMaterialChars() - totalMaterialChars));
          warnings.add("整卷材料累计超过上限，已压缩「" + sectionTitle + "」");
        }
        totalMaterialChars += materialContent.length();
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
        InterviewQuestionDTO dto = toQuestionDTO(raw, questionIndex, categoryKey, sectionTitle, warnings);
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

    String title = firstNonBlank(generated.paperTitle(), skill.name() + "模拟测验");
    PaperTree tree = PaperTree.recalculate(new PaperTree(
        PaperTree.CURRENT_VERSION,
        PaperNode.paper("n0", title, showSubjectCategory(skill, config), sectionNodes)));

    log.info("组卷完成: skill={}, 题数={}, 大题数={}, warnings={}",
        config.skillId(), flat.size(), sectionNodes.size(), warnings.size());
    return new GeneratedQuiz(tree, flat, skill.name(), warnings);
  }

  /**
   * 单题规范化：题型兜底、选项清洗补标签、题干与分值兜底。
   *
   * @return 不可用的题目返回 null（调用方跳过）
   */
  private InterviewQuestionDTO toQuestionDTO(GeneratedPaperDTO.Question raw, int questionIndex,
                                             String categoryKey, String categoryLabel,
                                             List<String> warnings) {
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
        ? raw.fullScore() : defaultScore(questionType);

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
        null);
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
    return TYPE_SINGLE_CHOICE.equals(type) || TYPE_MULTI_CHOICE.equals(type);
  }

  private int defaultScore(String questionType) {
    return switch (questionType) {
      case TYPE_SINGLE_CHOICE, TYPE_MULTI_CHOICE -> properties.getDefaultChoiceScore();
      case TYPE_FILL_BLANK -> properties.getDefaultFillScore();
      case TYPE_READING -> properties.getDefaultReadingScore();
      case TYPE_WRITING -> properties.getDefaultWritingScore();
      case TYPE_SHORT_ANSWER -> properties.getDefaultShortAnswerScore();
      default -> properties.getDefaultOtherScore();
    };
  }

  // ==================== 组卷参数渲染 ====================

  private List<SkillCategoryDTO> resolveCategories(SkillDTO skill, List<String> requested) {
    List<SkillCategoryDTO> all = skill.categories() != null ? skill.categories() : List.of();
    if (requested == null || requested.isEmpty()) {
      return all;
    }
    Set<String> wanted = new LinkedHashSet<>(requested);
    List<SkillCategoryDTO> filtered = all.stream()
        .filter(category -> wanted.contains(category.key()))
        .toList();
    return filtered.isEmpty() ? all : filtered;
  }

  /** 按板块 priority 与数量平均分配题量，余数补给前面的板块 */
  private Map<String, Integer> allocate(List<SkillCategoryDTO> categories, int total) {
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

  private String renderCategoryTable(List<SkillCategoryDTO> categories, Map<String, Integer> allocation) {
    if (categories.isEmpty()) {
      return "（未指定板块，请覆盖该学科的核心考点）";
    }
    StringBuilder builder = new StringBuilder();
    for (SkillCategoryDTO category : categories) {
      builder.append("| ").append(category.key())
          .append(" | ").append(category.label())
          .append(" | ").append(allocation.getOrDefault(category.key(), 1))
          .append(" |\n");
    }
    return builder.toString().trim();
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

  private String describeTypePreference(List<String> questionTypes) {
    if (questionTypes == null || questionTypes.isEmpty()) {
      return "按该学科常见考试题型自动配比（客观题与主观题兼顾）";
    }
    return "优先使用以下题型：" + String.join("、", questionTypes);
  }

  private String showSubjectCategory(SkillDTO skill, QuizConfig config) {
    return skill.name() + " · " + DifficultyLevel.labelOf(config.difficulty());
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
