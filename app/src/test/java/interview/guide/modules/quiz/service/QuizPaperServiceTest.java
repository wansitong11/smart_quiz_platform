package interview.guide.modules.quiz.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.quiz.config.QuizProperties;
import interview.guide.modules.quiz.model.GeneratedPaperDTO;
import interview.guide.modules.quiz.model.QuizConfig;
import interview.guide.modules.quiz.model.QuizSubjectSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("模拟测验组卷：阅读材料截断与题料一致性")
class QuizPaperServiceTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;

  @Mock
  private QuizCatalogService quizCatalogService;

  @Mock
  private InterviewPersistenceService persistenceService;

  private QuizProperties properties;
  private QuizPaperService service;

  @BeforeEach
  void setUp() throws Exception {
    properties = new QuizProperties();
    properties.setMaxMaterialCharsPerMaterial(10);
    properties.setMaxReadingMaterialCharsPerMaterial(20);
    properties.setMaxTotalMaterialChars(1000);
    properties.setMaxLongReadingSectionsPerPaper(1);
    service = new QuizPaperService(llmProviderRegistry, structuredOutputInvoker,
        quizCatalogService, persistenceService, properties, new DefaultResourceLoader());
  }

  @Test
  @DisplayName("材料在段落边界截断并追加标记")
  void shouldTruncateMaterialAtParagraphBoundary() {
    assertThat(QuizPaperService.truncateMaterial("段落一内容\n段落二内容", 5))
        .isEqualTo("段落一内容……（后文略）");
  }

  @Test
  @DisplayName("短材料不被截断")
  void shouldKeepShortMaterialUnchanged() {
    assertThat(QuizPaperService.truncateMaterial("短文", 10)).isEqualTo("短文");
  }

  @Test
  @DisplayName("考点中文名命中阅读关键词即视为阅读型板块")
  void shouldDetectReadingSectionByResolvedCategoryLabel() {
    QuizSubjectSpec spec = new QuizSubjectSpec("x", "托福考试", null, "other", "其他/自定义",
        List.of(new QuizSubjectSpec.Category("kp1", "阅读题")), List.of(), null, null, null);
    GeneratedPaperDTO.Section section =
        new GeneratedPaperDTO.Section("第一篇", "kp1", null, List.of());

    assertThat(QuizPaperService.isReadingSection(section, spec)).isTrue();
  }

  @Test
  @DisplayName("板块标题命中阅读关键词即视为阅读型板块")
  void shouldDetectReadingSectionByTitle() {
    GeneratedPaperDTO.Section section =
        new GeneratedPaperDTO.Section("阅读题", "kp1", null, List.of());

    assertThat(QuizPaperService.isReadingSection(section, null)).isTrue();
  }

  @Test
  @DisplayName("存在 READING 题型即视为阅读型板块")
  void shouldDetectReadingSectionByReadingQuestionType() {
    GeneratedPaperDTO.Question question =
        new GeneratedPaperDTO.Question("READING", "题干", List.of(), 5, null, null, List.of(), null);
    GeneratedPaperDTO.Section section =
        new GeneratedPaperDTO.Section("第一篇", "kp1", null, List.of(question));

    assertThat(QuizPaperService.isReadingSection(section, null)).isTrue();
  }

  @Test
  @DisplayName("普通选择题板块不视为阅读型")
  void shouldNotDetectReadingSectionForPlainChoice() {
    GeneratedPaperDTO.Section section = new GeneratedPaperDTO.Section(
        "基础", "kp2", null, List.of(choiceQuestion("SINGLE_CHOICE")));

    assertThat(QuizPaperService.isReadingSection(section, null)).isFalse();
  }

  @Test
  @DisplayName("阅读材料被截断时整板块丢弃，避免题料不一致")
  void shouldDropLongReadingSectionWhenMaterialTruncated() {
    QuizSubjectSpec spec = new QuizSubjectSpec("x", "托福考试", null, "other", "其他/自定义",
        List.of(new QuizSubjectSpec.Category("kp1", "阅读题")), List.of(), null, null, null);
    GeneratedPaperDTO.Section reading = new GeneratedPaperDTO.Section(
        "阅读题", "kp1", new GeneratedPaperDTO.Material("材料", "甲".repeat(100)),
        List.of(choiceQuestion("SINGLE_CHOICE")));
    GeneratedPaperDTO.Section plain = new GeneratedPaperDTO.Section(
        "基础", "kp2", null, List.of(choiceQuestion("SINGLE_CHOICE")));

    GeneratedPaperDTO generated =
        new GeneratedPaperDTO("卷", "x", List.of(reading, plain));
    QuizConfig config = new QuizConfig("x", "mid", 2, List.of(), List.of(), null);

    QuizPaperService.GeneratedQuiz result =
        service.toPaperTree(generated, config, spec, Map.of(), 2);

    assertThat(result.questions())
        .extracting(InterviewQuestionDTO::question)
        .containsExactly("普通选择题");
    assertThat(result.warnings()).anyMatch(w -> w.contains("丢弃"));
  }

  private GeneratedPaperDTO.Question choiceQuestion(String type) {
    return new GeneratedPaperDTO.Question(
        type,
        "普通选择题",
        List.of(new GeneratedPaperDTO.Option("A", "甲"), new GeneratedPaperDTO.Option("B", "乙")),
        2,
        "A",
        null,
        List.of(),
        null);
  }
}
