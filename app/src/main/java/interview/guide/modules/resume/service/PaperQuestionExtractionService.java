package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.resume.service.PaperExtractionResult.ExtractedQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 试卷 / 练习材料结构化切题服务。
 * <p>
 * 输入是从上传材料里解析出的纯文本，输出是「一道题一个对象」的结构化题目列表，
 * 供后续创建练习会话、逐题批改、错题本与掌握度统计使用。
 * </p>
 * <p>
 * 处理策略：先按题号正则把材料切成题目块（正则保证不漏题、不串题），
 * 再按字符预算分批交给模型补全题型、知识点、参考答案与评分标准，
 * 最后合并去重并做一致性校验。纯文字层材料可直接处理，扫描件与照片需要视觉模型，暂不支持。
 * </p>
 */
@Slf4j
@Service
public class PaperQuestionExtractionService {

    /** 行首题号：1. / 1． / 1、 / 1) / 1） */
    private static final Pattern QUESTION_NO_PATTERN =
        Pattern.compile("(?m)^\\s*(\\d{1,3})\\s*[.．、)）]\\s*");

    /** 一级大题标题：一、 / 二. */
    private static final Pattern MAJOR_HEADING_PATTERN =
        Pattern.compile("^\\s*([一二三四五六七八九十]{1,3})\\s*[、.．]\\s*(\\S.*)$");

    /** 大题正文达到该长度、且整段没有题号时，按「整段就是一道题」补一个题号 */
    private static final int MIN_UNNUMBERED_SECTION_CHARS = 30;

    /** 分批时向前携带的上下文长度：阅读类题目的材料通常写在题号之前 */
    private static final int CONTEXT_LOOKBACK_CHARS = 300;

    private static final Map<String, Integer> DEFAULT_FULL_SCORES = Map.of(
        "SINGLE_CHOICE", 3,
        "MULTI_CHOICE", 3,
        "FILL_BLANK", 4,
        "SHORT_ANSWER", 6,
        "READING", 6,
        "WRITING", 20,
        "OTHER", 4
    );

    private static final Set<String> SUBJECT_IDS = Set.of("chinese", "math", "english");

    /** 合法题型 */
    private static final Set<String> QUESTION_TYPES = Set.of(
        "SINGLE_CHOICE", "MULTI_CHOICE", "FILL_BLANK", "SHORT_ANSWER", "READING", "WRITING", "OTHER");

    /** 选项标签前缀：A. / A、 / A) / （A） / A： */
    private static final Pattern OPTION_PREFIX_PATTERN =
        Pattern.compile("^\\s*[（(]?([A-Ha-h])[）)]?\\s*[.、:：]?\\s*");

    /** 多选信号词 */
    private static final List<String> MULTI_CHOICE_HINTS =
        List.of("多选", "多项符合", "多项正确", "至少两项", "至少两个", "选出所有", "选出全部",
            "全部正确", "不止一个", "两个或两个以上");

    /** 写作信号词 */
    private static final List<String> WRITING_HINTS =
        List.of("作文", "不少于", "写一篇", "写作要求", "题目自拟");

    /** 填空信号词 */
    private static final List<String> FILL_BLANK_HINTS = List.of("填空", "补写", "默写", "______", "____");

    /** 单题最多识别的选项数 */
    private static final int MAX_OPTIONS = 10;

    private static final String UNKNOWN_ANSWER = "暂缺";

    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final InterviewSkillService skillService;
    private final PaperExtractionProperties properties;
    private final PaperStructureDetector structureDetector;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<ExtractionBatchDTO> outputConverter;

    public PaperQuestionExtractionService(
        LlmProviderRegistry llmProviderRegistry,
        StructuredOutputInvoker structuredOutputInvoker,
        InterviewSkillService skillService,
        PaperExtractionProperties properties,
        PaperStructureDetector structureDetector,
        ResourceLoader resourceLoader
    ) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.skillService = skillService;
        this.properties = properties;
        this.structureDetector = structureDetector;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(ExtractionBatchDTO.class);
    }

    /**
     * 结构化切题：材料文本 → 题目列表。
     *
     * @param paperText   试卷 / 练习材料文本
     * @param llmProvider 指定模型 Provider，为空时用默认 Provider
     */
    public PaperExtractionResult extract(String paperText, String llmProvider) {
        return extract(paperText, llmProvider, null);
    }

    /**
     * 结构化切题（可带题号 → 页码映射）。
     *
     * @param pageLocator 题干 → 页码定位器；扫描件识别时用于"查看原页"，文字材料传 null
     */
    public PaperExtractionResult extract(String paperText, String llmProvider,
                                         PageTextMerger.PageLocator pageLocator) {
        String text = normalizeText(paperText);
        if (text.isBlank()) {
            throw new BusinessException(ErrorCode.PAPER_PARSE_FAILED, "材料内容为空，无法切题");
        }
        // 很多试卷每个大题内部重新从 1 编号；切题链路（分批、去重、结构归属）要求题号全卷唯一，
        // 所以先做一次确定性的行首重排。模型看到的就是 1..N，不会再把同号题互相覆盖。
        text = renumberQuestions(ensureSectionQuestions(text));

        Map<String, String> categoryLabels = loadCategoryLabels();
        List<String> warnings = new ArrayList<>();

        // ① 结构识别（规则优先）：大题 → 小节 → 材料与题目区间
        PaperStructureDetector.PaperStructure structure = structureDetector.detect(text);
        List<PaperStructureDetector.Section> leafGroups = structure.allLeafGroups();
        log.info("试卷结构识别: 大题 {} 个, 叶子分组 {} 个",
            structure.sections().size(), leafGroups.size());

        // ② 整卷分批切题（按字符预算分批，避免长卷调用次数过多）
        ExtractionState state = new ExtractionState();
        List<ExtractedQuestion> extracted = extractQuestionsFromText(text, llmProvider, categoryLabels, state);
        warnings.addAll(state.warnings);

        List<ExtractedQuestion> ordered = dedupeAndSort(extracted);
        if (ordered.isEmpty()) {
            throw new BusinessException(ErrorCode.PAPER_NO_QUESTION_FOUND,
                "未从材料中识别到题目，请确认上传的是有文字层的电子版试卷或练习材料");
        }
        if (ordered.size() > properties.getMaxQuestions()) {
            warnings.add("题目数量超过上限 " + properties.getMaxQuestions() + "，超出部分已忽略");
            ordered = ordered.subList(0, properties.getMaxQuestions());
        }

        int totalScore = ordered.stream().mapToInt(ExtractedQuestion::fullScore).sum();
        String resolvedSubject = state.subjectId != null ? state.subjectId : InterviewDefaults.SKILL_ID;
        log.info("试卷切题完成: subject={}, 题目数={}, 卷面总分={}", resolvedSubject, ordered.size(), totalScore);

        // ③ 按题号把题目归入结构骨架，组装层级树并同时产出扁平题目列表
        Map<Integer, ExtractedQuestion> byQuestionNo = new LinkedHashMap<>();
        ordered.forEach(question -> byQuestionNo.putIfAbsent(question.questionNo(), question));
        TreeAssembly assembly = assembleTree(structure, byQuestionNo, warnings, pageLocator);

        List<InterviewQuestionDTO> questions = applyPageRanges(assembly.questions(), pageLocator);
        PaperTree paperTree = assembly.tree();
        return new PaperExtractionResult(resolvedSubject, paperTree, questions, totalScore, categoryLabels, warnings);
    }

    /**
     * 组装结果：层级树 + 扁平题目列表（questionIndex 与树中叶子顺序一致）。
     */
    private record TreeAssembly(PaperTree tree, List<InterviewQuestionDTO> questions) {}

    /**
     * 把结构骨架、材料与题目组装成层级树。题目按题号归入对应小节，
     * questionIndex 按树中顺序从 0 递增，与扁平题目列表一一对应。
     */
    private TreeAssembly assembleTree(PaperStructureDetector.PaperStructure structure,
                                      Map<Integer, ExtractedQuestion> byQuestionNo,
                                      List<String> warnings,
                                      PageTextMerger.PageLocator pageLocator) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        java.util.Set<Integer> usedQuestionNos = new java.util.LinkedHashSet<>();
        int[] idSeq = {0};
        List<PaperNode> sections = new ArrayList<>();
        List<PaperStructureDetector.Section> topSections = structure.sections();
        for (int i = 0; i < topSections.size(); i++) {
            sections.add(buildSectionNode(topSections.get(i), i + 1, byQuestionNo, questions,
                usedQuestionNos, idSeq, pageLocator));
        }

        List<ExtractedQuestion> orphans = byQuestionNo.entrySet().stream()
            .filter(entry -> !usedQuestionNos.contains(entry.getKey()))
            .map(Map.Entry::getValue)
            .toList();
        if (!orphans.isEmpty()) {
            warnings.add("有 " + orphans.size() + " 道题未能归属到任何小节，已挂到卷末");
            List<PaperNode> nodes = new ArrayList<>();
            int order = 1;
            for (ExtractedQuestion orphan : orphans) {
                InterviewQuestionDTO dto = toQuestionDTO(questions.size(), orphan, pageLocator);
                questions.add(dto);
                nodes.add(PaperNode.ofQuestion(order++, dto));
            }
            sections.add(PaperNode.section("n" + (++idSeq[0]), sections.size() + 1, "其他题目", null, nodes));
        }

        PaperNode root = PaperNode.paper("n0", "试卷", null, sections);
        PaperTree tree = PaperTree.recalculate(new PaperTree(PaperTree.CURRENT_VERSION, root));
        return new TreeAssembly(tree, questions);
    }

    private PaperNode buildSectionNode(PaperStructureDetector.Section section,
                                       int siblingOrder,
                                       Map<Integer, ExtractedQuestion> byQuestionNo,
                                       List<InterviewQuestionDTO> questions,
                                       java.util.Set<Integer> usedQuestionNos,
                                       int[] idSeq,
                                       PageTextMerger.PageLocator pageLocator) {
        String nodeId = "n" + (++idSeq[0]);
        List<PaperNode> children = new ArrayList<>();
        int childOrder = 1;
        if (section.materialText() != null && !section.materialText().isBlank()) {
            children.add(PaperNode.material("m" + (++idSeq[0]), childOrder++, "阅读材料", section.materialText()));
        }
        if (section.children() != null && !section.children().isEmpty()) {
            List<PaperStructureDetector.Section> subSections = section.children();
            for (int i = 0; i < subSections.size(); i++) {
                children.add(buildSectionNode(subSections.get(i), i + 1, byQuestionNo, questions,
                    usedQuestionNos, idSeq, pageLocator));
            }
        } else {
            for (Integer questionNo : section.questionNos()) {
                ExtractedQuestion extracted = byQuestionNo.get(questionNo);
                if (extracted == null || !usedQuestionNos.add(questionNo)) {
                    continue;
                }
                InterviewQuestionDTO dto = toQuestionDTO(questions.size(), extracted, pageLocator);
                questions.add(dto);
                children.add(PaperNode.ofQuestion(childOrder++, dto));
            }
        }
        return PaperNode.section(nodeId, siblingOrder, section.title(), section.subtitle(), children);
    }

    /**
     * 切题过程中的共享状态（学科、告警、题号回退计数）。
     */
    private static final class ExtractionState {
        private final List<String> warnings = new ArrayList<>();
        private String subjectId;
        private int fallbackOrder;
    }

    /**
     * 结构化切题：按字符预算分批送模型，返回整卷题目。
     */
    private List<ExtractedQuestion> extractQuestionsFromText(String groupText, String llmProvider,
                                                            Map<String, String> categoryLabels,
                                                            ExtractionState state) {
        List<QuestionBlock> blocks = splitByQuestionNo(groupText);
        List<List<QuestionBlock>> batches = groupIntoBatches(blocks, properties.getBatchCharLimit());

        List<ExtractedQuestion> questions = new ArrayList<>();
        for (List<QuestionBlock> batch : batches) {
            String batchText = renderBatchText(groupText, batch);
            ExtractionBatchDTO dto = callModel(batchText, batch, llmProvider);
            if (dto == null) {
                state.warnings.add("第 " + batch.get(0).questionNo() + " 题附近的分批处理未返回结果");
                continue;
            }
            if (state.subjectId == null && dto.subjectKey() != null
                && SUBJECT_IDS.contains(dto.subjectKey().trim())) {
                state.subjectId = dto.subjectKey().trim();
            }
            if (dto.questions() == null) {
                continue;
            }
            for (ExtractedQuestionDTO raw : dto.questions()) {
                state.fallbackOrder++;
                questions.add(toExtractedQuestion(raw, state.fallbackOrder, categoryLabels, state.warnings));
            }
        }
        return dedupeAndSort(questions);
    }

    /**
     * 把切题结果转换为练习会话使用的题目 DTO（索引按抽取顺序从 0 递增）。
     */
    public List<InterviewQuestionDTO> toQuestionDTOs(List<ExtractedQuestion> questions) {
        List<InterviewQuestionDTO> dtos = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            dtos.add(toQuestionDTO(i, questions.get(i)));
        }
        return dtos;
    }

    /**
     * 补全结束页：下一题的起始页就是本题的结束页（跨页题 from &lt; to）；
     * 最后一题收口在整卷末页。
     */
    private List<InterviewQuestionDTO> applyPageRanges(List<InterviewQuestionDTO> questions,
                                                       PageTextMerger.PageLocator locator) {
        if (locator == null) {
            return questions;
        }
        List<InterviewQuestionDTO> result = new ArrayList<>(questions.size());
        for (int i = 0; i < questions.size(); i++) {
            InterviewQuestionDTO question = questions.get(i);
            Integer from = question.sourcePageFrom();
            if (from == null) {
                result.add(question);
                continue;
            }
            Integer nextFrom = null;
            for (int j = i + 1; j < questions.size(); j++) {
                if (questions.get(j).sourcePageFrom() != null) {
                    nextFrom = questions.get(j).sourcePageFrom();
                    break;
                }
            }
            int to = nextFrom != null
                ? Math.max(from, nextFrom)
                : Math.max(from, locator.lastPageNo());
            result.add(question.withSourcePages(from, to));
        }
        return result;
    }

    /**
     * 单个抽取结果 → 题目 DTO（questionIndex 由调用方按树中顺序指定）。
     */
    private InterviewQuestionDTO toQuestionDTO(int index, ExtractedQuestion q) {
        return toQuestionDTO(index, q, null);
    }

    private InterviewQuestionDTO toQuestionDTO(int index, ExtractedQuestion q,
                                               PageTextMerger.PageLocator pageLocator) {
        int[] pages = pageLocator == null ? null : pageLocator.locate(q.toQuestionText());
        return new InterviewQuestionDTO(
            index,
            q.toQuestionText(),
            defaultString(q.questionType(), "OTHER"),
            defaultString(q.categoryLabel(), q.categoryKey()),
            truncate(String.join("、", q.knowledgePoints()), 300),
            null,
            null,
            null,
            false,
            null,
            defaultString(q.referenceAnswer(), UNKNOWN_ANSWER),
            q.knowledgePoints(),
            q.scoringRubric(),
            null,
            q.fullScore(),
            defaultString(q.questionType(), "OTHER"),
            q.options(),
            truncate(defaultString(q.analysis(), ""), 300),
            pages == null ? null : pages[0],
            pages == null ? null : pages[1]
        );
    }

    // ==================== 内部实现 ====================

    private ExtractionBatchDTO callModel(String batchText, List<QuestionBlock> batch, String llmProvider) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("subjectOptions", buildSubjectOptions());
        variables.put("scopeInstruction", buildScopeInstruction(batch));
        variables.put("paperText", batchText);

        String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
        String userPrompt = userPromptTemplate.render(variables);

        try {
            ChatClient chatClient = llmProviderRegistry.getPlainChatClient(llmProvider);
            return structuredOutputInvoker.invoke(
                chatClient,
                systemPrompt,
                userPrompt,
                outputConverter,
                ErrorCode.PAPER_EXTRACTION_FAILED,
                "试卷切题失败：",
                "试卷切题",
                log
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("试卷切题模型调用失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "试卷切题失败");
        }
    }

    private String buildScopeInstruction(List<QuestionBlock> batch) {
        QuestionBlock first = batch.get(0);
        QuestionBlock last = batch.get(batch.size() - 1);
        if (first.questionNo() <= 0) {
            return "本次请处理材料中出现的全部题目。";
        }
        return "本次只处理题号 " + first.questionNo() + " 到 " + last.questionNo()
            + " 的题目（共 " + batch.size() + " 道）。材料开头可能带有上一题的尾部或本题的阅读材料，"
            + "请只抽取上述题号范围内的题目，不要重复抽取范围之外的题目。";
    }

    private String buildSubjectOptions() {
        StringBuilder sb = new StringBuilder();
        for (InterviewSkillService.SkillDTO skill : safeAllSkills()) {
            sb.append("## ").append(skill.name()).append("（subjectKey=").append(skill.id()).append("）\n");
            if (skill.categories() != null) {
                for (InterviewSkillService.SkillCategoryDTO category : skill.categories()) {
                    sb.append("- ").append(category.key()).append(' ').append(category.label()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private Map<String, String> loadCategoryLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (InterviewSkillService.SkillDTO skill : safeAllSkills()) {
            if (skill.categories() == null) {
                continue;
            }
            for (InterviewSkillService.SkillCategoryDTO category : skill.categories()) {
                labels.putIfAbsent(category.key(), category.label());
            }
        }
        return labels;
    }

    private List<InterviewSkillService.SkillDTO> safeAllSkills() {
        try {
            return skillService.getAllSkills();
        } catch (Exception e) {
            log.warn("加载学科板块列表失败，切题 Prompt 将不包含板块选项: {}",
                ErrorLogSanitizer.summarize(e));
            return List.of();
        }
    }

    /**
     * 按行首题号切块。没有题号时整篇作为一块，交给模型判断。
     */
    List<QuestionBlock> splitByQuestionNo(String text) {
        List<QuestionBlock> blocks = new ArrayList<>();
        Matcher matcher = QUESTION_NO_PATTERN.matcher(text);
        List<int[]> marks = new ArrayList<>();
        while (matcher.find()) {
            marks.add(new int[] {Integer.parseInt(matcher.group(1)), matcher.start()});
        }
        if (marks.isEmpty()) {
            blocks.add(new QuestionBlock(0, text));
            return blocks;
        }
        // 首个题号之前的卷头 / 阅读材料并入第一块，避免丢材料
        int preambleEnd = marks.get(0)[1];
        String preamble = text.substring(0, preambleEnd);
        for (int i = 0; i < marks.size(); i++) {
            int start = marks.get(i)[1];
            int end = i + 1 < marks.size() ? marks.get(i + 1)[1] : text.length();
            String blockText = i == 0 ? preamble + text.substring(start, end) : text.substring(start, end);
            blocks.add(new QuestionBlock(marks.get(i)[0], blockText));
        }
        return blocks;
    }

    private List<List<QuestionBlock>> groupIntoBatches(List<QuestionBlock> blocks, int charLimit) {
        int limit = Math.max(500, charLimit);
        List<List<QuestionBlock>> batches = new ArrayList<>();
        List<QuestionBlock> current = new ArrayList<>();
        int currentChars = 0;
        for (QuestionBlock block : blocks) {
            int blockChars = block.text().length();
            if (!current.isEmpty() && currentChars + blockChars > limit) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(block);
            currentChars += blockChars;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /**
     * 渲染本批材料：向前多带一段上下文，保证阅读题的题干材料不被切断。
     */
    private String renderBatchText(String fullText, List<QuestionBlock> batch) {
        int start = Math.max(0, fullText.indexOf(batch.get(0).text()));
        int from = Math.max(0, start - CONTEXT_LOOKBACK_CHARS);
        int end = start;
        for (QuestionBlock block : batch) {
            int blockEnd = fullText.indexOf(block.text(), end) + block.text().length();
            end = Math.max(end, blockEnd);
        }
        if (end <= from) {
            end = fullText.length();
        }
        String batchText = fullText.substring(from, Math.min(end, fullText.length()));
        if (batchText.length() > properties.getMaxPaperChars()) {
            batchText = batchText.substring(0, properties.getMaxPaperChars());
        }
        return batchText;
    }

    private ExtractedQuestion toExtractedQuestion(ExtractedQuestionDTO raw, int fallbackOrder,
                                                  Map<String, String> categoryLabels,
                                                  List<String> warnings) {
        int questionNo = raw.questionNo() != null && raw.questionNo() > 0 ? raw.questionNo() : fallbackOrder;
        String rawType = raw.questionType() != null ? raw.questionType().trim().toUpperCase() : null;
        String categoryKey = raw.knowledgeCategory() != null ? raw.knowledgeCategory().trim() : null;
        String categoryLabel = categoryKey != null
            ? categoryLabels.getOrDefault(categoryKey, categoryKey)
            : null;
        List<String> knowledgePoints = raw.knowledgePoints() != null
            ? raw.knowledgePoints().stream().filter(p -> p != null && !p.isBlank()).toList()
            : List.of();

        // 选项结构化 + 题干去重 + 题型判定 + 参考答案规范化
        List<QuestionOption> options = parseOptions(raw.options(), questionNo, warnings);
        String questionText = stripDuplicatedOptions(defaultString(raw.question(), ""), options);
        String referenceAnswer = defaultString(raw.referenceAnswer(), UNKNOWN_ANSWER);
        String questionType = resolveQuestionType(rawType, questionText, options, referenceAnswer);
        referenceAnswer = normalizeChoiceAnswer(referenceAnswer, options, questionType);

        int fullScore = raw.fullScore() != null && raw.fullScore() > 0
            ? raw.fullScore()
            : DEFAULT_FULL_SCORES.getOrDefault(questionType, 4);
        if (UNKNOWN_ANSWER.equals(referenceAnswer)) {
            warnings.add("第 " + questionNo + " 题缺少参考答案，批改时按要点判分");
        }
        if (raw.note() != null && !raw.note().isBlank()) {
            warnings.add("第 " + questionNo + " 题：" + raw.note().trim());
        }
        if (rawType == null || !QUESTION_TYPES.contains(rawType)) {
            warnings.add("第 " + questionNo + " 题题型为推断值：" + questionType);
        }
        return new ExtractedQuestion(
            questionNo,
            questionType,
            questionText,
            options,
            fullScore,
            categoryKey,
            categoryLabel,
            knowledgePoints,
            referenceAnswer,
            raw.scoringRubric(),
            raw.analysis(),
            raw.note()
        );
    }

    /**
     * 选项清洗：去掉模型可能带上的标签，统一按 A/B/C/D 重新编号；
     * 有效选项少于 2 个时返回 null（该题退化为文本作答）。
     */
    List<QuestionOption> parseOptions(List<String> rawOptions, int questionNo, List<String> warnings) {
        if (rawOptions == null || rawOptions.isEmpty()) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        for (String raw : rawOptions) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String trimmed = raw.trim();
            // 只有选项标签没有正文（如 "A."、"B"、"（C）"）视为空选项
            if (isLabelOnly(trimmed)) {
                warnings.add("第 " + questionNo + " 题存在空选项，已忽略");
                continue;
            }
            texts.add(stripOptionPrefix(trimmed));
        }
        if (texts.size() < 2) {
            if (!texts.isEmpty()) {
                warnings.add("第 " + questionNo + " 题只识别到 1 个选项，已改为文本作答");
            }
            return null;
        }
        if (texts.size() > MAX_OPTIONS) {
            warnings.add("第 " + questionNo + " 题选项超过 " + MAX_OPTIONS + " 个，已截断");
            texts = new ArrayList<>(texts.subList(0, MAX_OPTIONS));
        }
        List<QuestionOption> options = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            options.add(new QuestionOption(String.valueOf((char) ('A' + i)), texts.get(i)));
        }
        return options;
    }

    /**
     * 判断选项文本是否只有标签（没有正文）。
     */
    private boolean isLabelOnly(String text) {
        return text.matches("[（(]?[A-Ha-h][）)]?\\s*[.、:：]?");
    }

    /**
     * 去掉选项文本前面的标签（A. / （B） / C、 / D：），并剥掉多余空白。
     */
    private String stripOptionPrefix(String text) {
        Matcher matcher = OPTION_PREFIX_PATTERN.matcher(text);
        if (matcher.find() && matcher.end() > 0) {
            String rest = text.substring(matcher.end()).trim();
            if (!rest.isBlank()) {
                return rest;
            }
        }
        return text.trim();
    }

    /**
     * 题干去重：模型有时把选项同时写进题干，这里按行去掉与选项正文重复的行。
     */
    private String stripDuplicatedOptions(String questionText, List<QuestionOption> options) {
        if (options == null || options.isEmpty() || questionText.isBlank()) {
            return questionText;
        }
        Set<String> optionTexts = options.stream()
            .map(option -> normalizeForCompare(option.text()))
            .collect(java.util.stream.Collectors.toSet());
        List<String> kept = new ArrayList<>();
        for (String line : questionText.split("\n")) {
            String normalized = normalizeForCompare(stripOptionPrefix(line));
            if (!normalized.isBlank() && optionTexts.contains(normalized)) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept).trim();
    }

    /**
     * 题型判定：优先采用模型给出的合法题型，缺失或非法时按规则兜底。
     */
    String resolveQuestionType(String rawType, String questionText, List<QuestionOption> options,
                               String referenceAnswer) {
        String candidate = rawType != null ? rawType.trim().toUpperCase() : "";
        boolean hasOptions = options != null && options.size() >= 2;
        String text = questionText != null ? questionText : "";

        if (QUESTION_TYPES.contains(candidate) && !"OTHER".equals(candidate)) {
            if ("SINGLE_CHOICE".equals(candidate) && hasOptions && containsAny(text, MULTI_CHOICE_HINTS)) {
                return "MULTI_CHOICE";
            }
            return candidate;
        }
        if (hasOptions) {
            if (containsAny(text, MULTI_CHOICE_HINTS) || countChoiceLetters(referenceAnswer) > 1) {
                return "MULTI_CHOICE";
            }
            return "SINGLE_CHOICE";
        }
        if (containsAny(text, WRITING_HINTS)) {
            return "WRITING";
        }
        if (containsAny(text, FILL_BLANK_HINTS) || text.contains("（）") || text.contains("()")) {
            return "FILL_BLANK";
        }
        return "SHORT_ANSWER";
    }

    /**
     * 选择题参考答案规范化：抽取选项字母并按升序拼成 "BD"，抽不出字母时原样返回。
     */
    String normalizeChoiceAnswer(String referenceAnswer, List<QuestionOption> options, String questionType) {
        if (referenceAnswer == null || referenceAnswer.isBlank() || options == null || options.isEmpty()) {
            return referenceAnswer;
        }
        if (!"SINGLE_CHOICE".equals(questionType) && !"MULTI_CHOICE".equals(questionType)) {
            return referenceAnswer;
        }
        Set<String> validKeys = options.stream().map(QuestionOption::key)
            .collect(java.util.stream.Collectors.toSet());
        Matcher matcher = Pattern.compile("[A-Ha-h]").matcher(referenceAnswer);
        Set<String> letters = new java.util.TreeSet<>();
        while (matcher.find()) {
            String upper = matcher.group().toUpperCase();
            if (validKeys.contains(upper)) {
                letters.add(upper);
            }
        }
        if (letters.isEmpty()) {
            return referenceAnswer;
        }
        return String.join("", letters);
    }

    private int countChoiceLetters(String referenceAnswer) {
        if (referenceAnswer == null || referenceAnswer.isBlank()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("[A-Ha-h]").matcher(referenceAnswer);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean containsAny(String text, List<String> hints) {
        for (String hint : hints) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForCompare(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "").trim();
    }

    /**
     * 按题号去重（保留首个）并升序排序，避免分批边界处重复抽取。
     */
    private List<ExtractedQuestion> dedupeAndSort(List<ExtractedQuestion> questions) {
        Map<Integer, ExtractedQuestion> unique = new LinkedHashMap<>();
        for (ExtractedQuestion question : questions) {
            unique.putIfAbsent(question.questionNo(), question);
        }
        List<ExtractedQuestion> result = new ArrayList<>(unique.values());
        result.sort((a, b) -> Integer.compare(a.questionNo(), b.questionNo()));
        return result;
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() > properties.getMaxPaperChars() * 2) {
            normalized = normalized.substring(0, properties.getMaxPaperChars() * 2);
        }
        return normalized;
    }

    /**
     * 把行首题号按出现顺序重排为 1..N，保留原分隔符与行内其它内容。
     * <p>
     * 只改「1. / 2、/ 3）」这类行首编号；题干正文里的数字不动。
     * </p>
     */
    static String renumberQuestions(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int sequence = 0;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher matcher = QUESTION_NO_PATTERN.matcher(line);
            if (matcher.find()) {
                sequence++;
                out.append(line, 0, matcher.start(1))
                    .append(sequence)
                    .append(line, matcher.end(1), line.length());
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * 给「整段没有题号」的大题补一个行首题号。
     * <p>
     * 典型场景：「一、看拼音写词语」和「七、作文」整段就是一道题，原文没有 "1."，
     * 而切题是按行首题号切块的，不补就会整段丢失。
     * 只对正文足够长的大题生效，避免把「注意事项」这类短说明当成题目。
     * </p>
     */
    static String ensureSectionQuestions(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        List<Integer> headings = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (MAJOR_HEADING_PATTERN.matcher(lines[i]).matches()) {
                headings.add(i);
            }
        }
        if (headings.isEmpty()) {
            return text;
        }

        StringBuilder out = new StringBuilder(text.length() + 128);
        for (int i = 0; i < lines.length; i++) {
            out.append(lines[i]);
            if (headings.contains(i)) {
                int nextHeading = lines.length;
                for (Integer heading : headings) {
                    if (heading > i) {
                        nextHeading = heading;
                        break;
                    }
                }
                boolean hasNumberedQuestion = false;
                int sectionChars = 0;
                for (int j = i + 1; j < nextHeading; j++) {
                    if (QUESTION_NO_PATTERN.matcher(lines[j]).find()) {
                        hasNumberedQuestion = true;
                        break;
                    }
                    sectionChars += lines[j].strip().length();
                }
                Matcher heading = MAJOR_HEADING_PATTERN.matcher(lines[i]);
                if (!hasNumberedQuestion && sectionChars >= MIN_UNNUMBERED_SECTION_CHARS
                    && heading.matches()) {
                    out.append('\n').append("1. ").append(heading.group(2).trim());
                }
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 题目块：题号 + 该题在材料中的原文片段。
     */
    record QuestionBlock(int questionNo, String text) {}

    /** 模型单批返回结构 */
    record ExtractionBatchDTO(String subjectKey, List<ExtractedQuestionDTO> questions) {}

    /** 模型单题返回结构 */
    record ExtractedQuestionDTO(
        Integer questionNo,
        String questionType,
        String question,
        List<String> options,
        Integer fullScore,
        String knowledgeCategory,
        List<String> knowledgePoints,
        String referenceAnswer,
        String scoringRubric,
        String analysis,
        String note
    ) {}
}
