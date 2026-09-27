package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.resume.service.PaperExtractionResult.ExtractedQuestion;
import interview.guide.modules.resume.service.PaperQuestionExtractionService.ExtractedQuestionDTO;
import interview.guide.modules.resume.service.PaperQuestionExtractionService.ExtractionBatchDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("试卷结构化切题服务测试")
class PaperQuestionExtractionServiceTest {

  private static final Pattern SCOPE_PATTERN = Pattern.compile("题号 (\\d+) 到 (\\d+)");

  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;
  @Mock
  private InterviewSkillService skillService;

  private PaperExtractionProperties properties;
  private PaperQuestionExtractionService service;

  @BeforeEach
  void setUp() throws Exception {
    properties = new PaperExtractionProperties();
    service = new PaperQuestionExtractionService(llmProviderRegistry, structuredOutputInvoker,
        skillService, properties, new PaperStructureDetector(), new DefaultResourceLoader());
    when(skillService.getAllSkills()).thenReturn(List.of(
        new InterviewSkillService.SkillDTO("chinese", "语文", "高中语文",
            List.of(new InterviewSkillService.SkillCategoryDTO("MODERN_READING", "现代文阅读", "CORE", null, true)),
            true, null, null, null)));
  }

  private ExtractedQuestionDTO question(int no, Integer fullScore, String referenceAnswer) {
    return new ExtractedQuestionDTO(no, "SINGLE_CHOICE", "第 " + no + " 题的题干",
        List.of("A. 选项一", "B. 选项二"), fullScore, "MODERN_READING",
        List.of("字音", "字形"), referenceAnswer, "选对得 3 分", "第 1 项符合原文", null);
  }

  private ExtractedQuestionDTO rawQuestion(int no, String type, String stem, List<String> options,
                                           String referenceAnswer) {
    return new ExtractedQuestionDTO(no, type, stem, options, 5, "MODERN_READING",
        List.of("字音"), referenceAnswer, "选对得分", null, null);
  }

  @Nested
  @DisplayName("选项清洗与题型判定")
  class OptionAndTypeTests {

    @Test
    @DisplayName("模型没带选项标签时按 A/B/C/D 自动补齐")
    void shouldLabelOptionsWithoutPrefix() {
      List<String> warnings = new ArrayList<>();
      List<QuestionOption> options = service.parseOptions(
          List.of("(-∞,0]∪(1,+∞)", "（B）(0,1]", "C、[2,+∞)", "D. ∅"), 1, warnings);

      assertThat(options).isNotNull();
      assertThat(options).extracting(QuestionOption::key).containsExactly("A", "B", "C", "D");
      assertThat(options).extracting(QuestionOption::text)
          .containsExactly("(-∞,0]∪(1,+∞)", "(0,1]", "[2,+∞)", "∅");
      assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("空选项被忽略，不足两个选项时降级为文本作答并告警")
    void shouldDropEmptyOptionsAndDowngrade() {
      List<String> warnings = new ArrayList<>();

      assertThat(service.parseOptions(List.of("A. ", "B", "C. 有效选项"), 2, warnings)).isNull();
      assertThat(service.parseOptions(List.of("A. 唯一选项"), 3, warnings)).isNull();
      assertThat(warnings).anyMatch(w -> w.contains("空选项"));
      assertThat(warnings).anyMatch(w -> w.contains("只识别到 1 个选项"));
    }

    @Test
    @DisplayName("选项超过上限时截断并告警")
    void shouldTruncateTooManyOptions() {
      List<String> warnings = new ArrayList<>();
      List<String> many = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        many.add("选项" + i);
      }

      List<QuestionOption> options = service.parseOptions(many, 4, warnings);

      assertThat(options).hasSize(10);
      assertThat(warnings).anyMatch(w -> w.contains("选项超过"));
    }

    @Test
    @DisplayName("题干里重复出现的选项行会被清理")
    void shouldStripDuplicatedOptionsFromStem() {
      givenModelReturns(new ExtractionBatchDTO("chinese", List.of(
          rawQuestion(1, "SINGLE_CHOICE", "下列读音正确的一项是\nA. 甲\nB. 乙",
              List.of("A. 甲", "B. 乙"), "A"))));

      PaperExtractionResult result = service.extract("1. 下列读音正确的一项是\nA. 甲\nB. 乙", null);

      InterviewQuestionDTO converted = result.questions().get(0);
      assertThat(converted.question()).startsWith("1. 下列读音正确的一项是");
      assertThat(converted.options()).hasSize(2);
      assertThat(converted.questionType()).isEqualTo("SINGLE_CHOICE");
      assertThat(converted.referenceAnswer()).isEqualTo("A");
    }

    @Test
    @DisplayName("题型兜底：有选项按单选、含多选信号按多选、含空线按填空、含作文按写作")
    void shouldResolveQuestionType() {
      List<QuestionOption> options = List.of(new QuestionOption("A", "甲"), new QuestionOption("B", "乙"));

      assertThat(service.resolveQuestionType("OTHER", "下列说法正确的是", options, "A"))
          .isEqualTo("SINGLE_CHOICE");
      assertThat(service.resolveQuestionType(null, "下列说法正确的有（多选）", options, "AB"))
          .isEqualTo("MULTI_CHOICE");
      assertThat(service.resolveQuestionType("SINGLE_CHOICE", "下列结论正确的是(多选)", options, "A"))
          .isEqualTo("MULTI_CHOICE");
      assertThat(service.resolveQuestionType(null, "补写出下列句子中的空缺部分 ______", null, "一览众山小"))
          .isEqualTo("FILL_BLANK");
      assertThat(service.resolveQuestionType(null, "请以「坚持」为题写一篇作文，不少于 800 字", null, "暂缺"))
          .isEqualTo("WRITING");
      assertThat(service.resolveQuestionType("MULTI_CHOICE", "下列结论正确的是", options, "AB"))
          .isEqualTo("MULTI_CHOICE");
      // 「多项式」不应被当成多选信号
      assertThat(service.resolveQuestionType(null, "该多项式各项系数之和为", options, "D"))
          .isEqualTo("SINGLE_CHOICE");
      assertThat(service.resolveQuestionType("SINGLE_CHOICE", "该多项式各项系数之和为", options, "D"))
          .isEqualTo("SINGLE_CHOICE");
    }

    @Test
    @DisplayName("选择题参考答案规范化为选项字母组合")
    void shouldNormalizeChoiceAnswer() {
      List<QuestionOption> options = List.of(new QuestionOption("A", "甲"),
          new QuestionOption("B", "乙"), new QuestionOption("C", "丙"), new QuestionOption("D", "丁"));

      assertThat(service.normalizeChoiceAnswer("答案是 B", options, "SINGLE_CHOICE")).isEqualTo("B");
      assertThat(service.normalizeChoiceAnswer("B、D", options, "MULTI_CHOICE")).isEqualTo("BD");
      assertThat(service.normalizeChoiceAnswer("D 和 B", options, "MULTI_CHOICE")).isEqualTo("BD");
      assertThat(service.normalizeChoiceAnswer("因为结构决定", options, "SINGLE_CHOICE"))
          .isEqualTo("因为结构决定");
    }
  }

  private void givenModelReturns(ExtractionBatchDTO batch) {
    doReturn(batch).when(structuredOutputInvoker).invoke(
        any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any());
  }

  @Nested
  @DisplayName("题号切块")
  class SplitTests {

    @Test
    @DisplayName("按行首题号切块，并把卷头材料并入第一块")
    void shouldSplitByQuestionNoAndKeepPreamble() {
      String text = "语文试卷\n阅读下面的材料，完成 1～2 题。\n"
          + "1. 第一题题干\nA. 甲\nB. 乙\n"
          + "2. 第二题题干\nA. 丙\nB. 丁\n";

      List<PaperQuestionExtractionService.QuestionBlock> blocks = service.splitByQuestionNo(text);

      assertThat(blocks).hasSize(2);
      assertThat(blocks.get(0).questionNo()).isEqualTo(1);
      assertThat(blocks.get(0).text()).contains("阅读下面的材料");
      assertThat(blocks.get(1).questionNo()).isEqualTo(2);
      assertThat(blocks.get(1).text()).doesNotContain("阅读下面的材料");
    }

    @Test
    @DisplayName("没有题号时整篇作为一个块")
    void shouldReturnWholeTextWhenNoQuestionNo() {
      List<PaperQuestionExtractionService.QuestionBlock> blocks =
          service.splitByQuestionNo("请以「坚持」为题写一篇不少于 800 字的作文。");

      assertThat(blocks).hasSize(1);
      assertThat(blocks.get(0).questionNo()).isZero();
    }
  }

  @Nested
  @DisplayName("切题结果")
  class ExtractionTests {

    @Test
    @DisplayName("单批切题时补全分值、知识点与参考答案，并统计卷面总分")
    void shouldExtractSingleBatch() {
      givenModelReturns(new ExtractionBatchDTO("chinese", List.of(question(1, 3, "A"), question(2, null, "暂缺"))));

      PaperExtractionResult result = service.extract("1. 题干一\n2. 题干二", null);

      assertThat(result.subjectId()).isEqualTo("chinese");
      assertThat(result.questions()).hasSize(2);
      assertThat(result.questions().get(0).category()).isEqualTo("现代文阅读");
      // 未给出分值时按题型默认值兜底（单选 3 分）
      assertThat(result.questions().get(1).fullScore()).isEqualTo(3);
      assertThat(result.totalScore()).isEqualTo(6);
      assertThat(result.warnings()).anyMatch(w -> w.contains("缺少参考答案"));
    }

    @Test
    @DisplayName("分批切题时按题号去重并升序合并")
    void shouldMergeBatchesByQuestionNo() {
      // 单批上限 600 字符，每题约 400 字符，保证每题各成一批
      properties.setBatchCharLimit(600);
      List<String> scopes = new ArrayList<>();
      doAnswer(invocation -> {
        String userPrompt = invocation.getArgument(2, String.class);
        Matcher matcher = SCOPE_PATTERN.matcher(userPrompt);
        if (!matcher.find()) {
          return new ExtractionBatchDTO("chinese", List.of());
        }
        scopes.add(matcher.group(1) + "-" + matcher.group(2));
        int from = Integer.parseInt(matcher.group(1));
        return new ExtractionBatchDTO("chinese", List.of(question(from, 3, "A")));
      }).when(structuredOutputInvoker).invoke(
          any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any());

      String pad = "题干".repeat(200);
      String text = "1. " + pad + "\n2. " + pad + "\n3. " + pad;
      PaperExtractionResult result = service.extract(text, null);

      assertThat(scopes).hasSize(3);
      assertThat(result.questions()).extracting(InterviewQuestionDTO::questionIndex)
          .containsExactly(0, 1, 2);
      assertThat(result.totalScore()).isEqualTo(9);
    }

    @Test
    @DisplayName("模型返回空题目时抛出业务异常")
    void shouldThrowWhenNoQuestionFound() {
      givenModelReturns(new ExtractionBatchDTO("chinese", List.of()));

      assertThatThrownBy(() -> service.extract("1. 题干", null))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("未从材料中识别到题目");
    }
  }

  @Nested
  @DisplayName("题目 DTO 转换")
  class ConversionTests {

    @Test
    @DisplayName("转换时拼出题号与选项、带上分值与知识点摘要")
    void shouldConvertToQuestionDTOs() {
      ExtractedQuestion extracted = new ExtractedQuestion(8, "SINGLE_CHOICE", "下列理解不正确的一项是",
          List.of(new InterviewQuestionDTO.QuestionOption("A", "甲"), new InterviewQuestionDTO.QuestionOption("B", "乙")),
          3, "ANCIENT_POETRY", "古诗文阅读",
          List.of("诗歌鉴赏·表达技巧"), "B", "选对得 3 分", "甲项与原文不符。", null);
      List<InterviewQuestionDTO> dtos = service.toQuestionDTOs(List.of(extracted));

      assertThat(dtos).hasSize(1);
      InterviewQuestionDTO dto = dtos.get(0);
      assertThat(dto.questionIndex()).isZero();
      assertThat(dto.question()).startsWith("8. 下列理解不正确的一项是").contains("A. 甲");
      assertThat(dto.category()).isEqualTo("古诗文阅读");
      assertThat(dto.type()).isEqualTo("SINGLE_CHOICE");
      assertThat(dto.fullScore()).isEqualTo(3);
      assertThat(dto.referenceAnswer()).isEqualTo("B");
      assertThat(dto.keyPoints()).containsExactly("诗歌鉴赏·表达技巧");
      assertThat(dto.topicSummary()).isEqualTo("诗歌鉴赏·表达技巧");
      assertThat(dto.analysis()).isEqualTo("甲项与原文不符。");
    }

    @Test
    @DisplayName("参考答案缺失时用「暂缺」占位，保证批改环节有兜底")
    void shouldFallbackReferenceAnswer() {
      ExtractedQuestion extracted = new ExtractedQuestion(1, "SHORT_ANSWER", "简述你的看法",
          List.of(), 6, "MODERN_READING", "现代文阅读", List.of(), null, null, null, null);
      assertThat(service.toQuestionDTOs(List.of(extracted)).get(0).referenceAnswer()).isEqualTo("暂缺");
    }
  }
}
