package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.TestBoundedExecutor;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.aicost.service.AiUsageGate;
import interview.guide.modules.aicost.service.FreeModelConcurrencyGate;
import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.service.AnswerKeyPreparationService.AnswerBatchDTO;
import interview.guide.modules.resume.service.AnswerKeyPreparationService.AnalysisBatchDTO;
import interview.guide.modules.resume.service.AnswerKeyPreparationService.GeneratedAnalysisDTO;
import interview.guide.modules.resume.service.AnswerKeyPreparationService.GeneratedAnswerDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("答案主求解、复核与裁决组合流程")
class AnswerKeyConsistencyIntegrationTest {

  private final List<BoundedExecutor> executors = new ArrayList<>();

  @Mock
  private LlmProviderRegistry providerRegistry;
  @Mock
  private StructuredOutputInvoker invoker;
  @Mock
  private PaperAssetService assetService;
  @Mock
  private PaperPageRepository pageRepository;
  @Mock
  private FileStorageService storageService;
  @Mock
  private PageImageNormalizer imageNormalizer;
  @Mock
  private PaperStructureService structureService;
  @Mock
  private ChatClient chatClient;
  @Mock
  private AiUsageGate usageGate;

  @AfterEach
  void tearDown() {
    executors.forEach(BoundedExecutor::shutdown);
  }

  @Test
  @DisplayName("主答案 A、复核 B、裁决 B 时最终采用 B，并生成一次解析")
  void shouldResolveConflictingAnswersAndGenerateAnalysis() throws Exception {
    AnswerKeyPreparationService service = service();
    InterviewQuestionDTO question = choiceQuestion(0);
    PaperTree tree = PaperTree.fromFlatQuestions(List.of(question), "数学");
    PaperStructureService.BuiltStructure built = new PaperStructureService.BuiltStructure(
        "math", tree, List.of(question), 5, List.of());
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(providerRegistry.getPlainReasoningChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(1L), eq(built), any()))
        .thenReturn(built);

    AtomicInteger answerCalls = new AtomicInteger();
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String userPrompt = invocation.getArgument(2);
      if (userPrompt.contains("已确认答案：")) {
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            0, "最终学生解析", List.of("几何概型"), "选对得分")));
      }
      int call = answerCalls.incrementAndGet();
      String selected = call == 1 ? "A" : "B";
      return new AnswerBatchDTO(List.of(new GeneratedAnswerDTO(
          0, selected, List.of(selected), "面积比清晰", false, 80)));
    });

    service.prepare(1L, built);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<Integer, AnswerKeyDTO>> keysCaptor = ArgumentCaptor.forClass(Map.class);
    verify(structureService, atLeastOnce())
        .applyAndStoreAnswerKeys(eq(1L), eq(built), keysCaptor.capture());
    AnswerKeyDTO key = keysCaptor.getAllValues().getLast().get(0);
    assertThat(key.referenceAnswer()).isEqualTo("B");
    assertThat(key.resolutionStatus()).isEqualTo(AnswerConsistencyResolver.STATUS_MAJORITY);
    assertThat(key.candidates()).hasSize(3);
    assertThat(key.analysis()).isEqualTo("最终学生解析");
    verify(providerRegistry, times(1)).getPlainReasoningChatClient();
    verify(invoker, times(4)).invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class));
  }

  @Test
  @DisplayName("单题解析失败且补跑仍失败时明确标记缺失，不伪造解析")
  void shouldMarkMissingAnalysisWhenQuestionAnalysisFails() throws Exception {
    AnswerKeyPreparationService service = service();
    List<InterviewQuestionDTO> questions = List.of(choiceQuestion(0), choiceQuestion(1));
    PaperTree tree = PaperTree.fromFlatQuestions(questions, "数学");
    PaperStructureService.BuiltStructure built = new PaperStructureService.BuiltStructure(
        "math", tree, questions, 10, List.of());
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(1L), eq(built), any()))
        .thenReturn(built);

    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String userPrompt = invocation.getArgument(2);
      if (userPrompt.contains("已确认答案：")) {
        int index = Pattern.compile("\\[questionIndex=(\\d+)]")
            .matcher(userPrompt)
            .results()
            .findFirst()
            .map(match -> Integer.parseInt(match.group(1)))
            .orElseThrow();
        if (index == 1) {
          throw new RuntimeException("UnexpectedEndOfInputException");
        }
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            index, "第" + (index + 1) + "题完整解析", List.of("考点"), "评分规则")));
      }
      return new AnswerBatchDTO(List.of(
          new GeneratedAnswerDTO(0, "B", List.of("B"), null, false, 90),
          new GeneratedAnswerDTO(1, "B", List.of("B"), null, false, 90)));
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared = service.prepare(1L, built);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<Integer, AnswerKeyDTO>> keysCaptor = ArgumentCaptor.forClass(Map.class);
    verify(structureService, atLeastOnce())
        .applyAndStoreAnswerKeys(eq(1L), eq(built), keysCaptor.capture());
    Map<Integer, AnswerKeyDTO> finalKeys = keysCaptor.getAllValues().getLast();
    assertThat(finalKeys.values())
        .allSatisfy(key -> assertThat(key.analysis()).isNotBlank());
    assertThat(finalKeys.get(1).analysis()).contains("解析未生成");
    assertThat(prepared.warnings()).anyMatch(warning -> warning.contains("第 2 题解析未生成"));
    verify(providerRegistry, never()).getPlainReasoningChatClient();
    verify(invoker, times(5)).invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class));
  }

  private AnswerKeyPreparationService service() throws Exception {
    BoundedExecutor answerExecutor = TestBoundedExecutor.create(2, 20);
    executors.add(answerExecutor);
    PaperAnswerExecutorProperties properties = new PaperAnswerExecutorProperties();
    properties.setAnalysisBatchSize(1);
    return new AnswerKeyPreparationService(
        providerRegistry, invoker, assetService, pageRepository, storageService,
        imageNormalizer, structureService, new AnswerConsistencyResolver(),
        new DefaultResourceLoader(), new AnswerBatchRunner(answerExecutor),
        new PaperAnalysisPacer(usageGate, properties), properties,
        mock(FreeModelConcurrencyGate.class), new AnalysisBatchScheduler(answerExecutor));
  }

  private InterviewQuestionDTO choiceQuestion(int index) {
    return new InterviewQuestionDTO(
        index, "在三角形中求概率", "math", "几何", null, "C", null, null,
        false, null, "暂缺", List.of(), null, null, 5,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(
            new QuestionOption("A", "(6-π)/6"),
            new QuestionOption("B", "(12-π)/12"),
            new QuestionOption("C", "π/6"),
            new QuestionOption("D", "π/12")),
        null, 1, 1, null, null, null, false);
  }
}
