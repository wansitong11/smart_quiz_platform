package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.VisionRequestRejectedException;
import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.TestBoundedExecutor;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.aicost.service.AiUsageGate;
import interview.guide.modules.aicost.service.FreeModelConcurrencyGate;
import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.service.AnswerKeyPreparationService.AnswerBatchDTO;
import interview.guide.modules.resume.service.AnswerKeyPreparationService.AnalysisBatchDTO;
import interview.guide.modules.resume.service.AnswerKeyPreparationService.GeneratedAnalysisDTO;
import interview.guide.modules.resume.service.AnswerKeyPreparationService.GeneratedAnswerDTO;
import interview.guide.modules.resume.service.AnswerKeyPreparationService.SubjectiveReviewBatchDTO;
import interview.guide.modules.resume.service.AnswerKeyPreparationService.SubjectiveReviewDTO;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("答案键批次并发与归位")
class AnswerKeyPreparationConcurrencyTest {

  private static final Long PAPER_ID = 1L;
  private static final Pattern QUESTION_INDEX =
      Pattern.compile("\\[questionIndex=(\\d+)]");

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

  private final List<BoundedExecutor> executors = new ArrayList<>();

  @AfterEach
  void tearDown() {
    executors.forEach(BoundedExecutor::shutdown);
  }

  @Test
  @DisplayName("解析并行按题号归位，队列满时等待在途任务后继续提交，不回退消费线程")
  void shouldMergeParallelAnalysesAndRunRemainingWhenRejected() throws Exception {
    BoundedExecutor executor = executor(1, 1);
    List<InterviewQuestionDTO> questions =
        List.of(choiceQuestion(0), choiceQuestion(1), choiceQuestion(2));
    PaperStructureService.BuiltStructure built = built(questions);
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    AtomicReference<String> rejectedAnalysisThread = new AtomicReference<>();
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        int questionIndex = firstQuestionIndex(prompt);
        if (questionIndex == 2) {
          rejectedAnalysisThread.set(Thread.currentThread().getName());
        }
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            questionIndex, "解析-" + questionIndex, List.of("考点"), "评分规则")));
      }
      return answers(prompt, "A");
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor).prepare(PAPER_ID, built);

    Map<Integer, AnswerKeyDTO> keys = capturedKeys(built);
    assertThat(keys.values()).allSatisfy(key ->
        assertThat(key.referenceAnswer()).isEqualTo("A"));
    assertThat(keys.get(0).analysis()).isEqualTo("解析-0");
    assertThat(keys.get(1).analysis()).isEqualTo("解析-1");
    assertThat(keys.get(2).analysis()).isEqualTo("解析-2");
    assertThat(prepared.warnings()).isEmpty();
    assertThat(rejectedAnalysisThread.get()).isNotNull();
    assertThat(rejectedAnalysisThread.get()).isNotEqualTo(Thread.currentThread().getName());
  }

  @Test
  @DisplayName("结构切题已有答案直接复用，不再调用答案键模型")
  void shouldReuseStructureAnswerWithoutCallingModel() throws Exception {
    BoundedExecutor executor = executor(1, 5);
    InterviewQuestionDTO question = structuredAnswerQuestion(0);
    PaperStructureService.BuiltStructure built = built(List.of(question));

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor).prepare(PAPER_ID, built);

    assertThat(prepared.structure()).isSameAs(built);
    verifyNoInteractions(invoker, structureService);
  }

  @Test
  @DisplayName("答案齐全但解析缺失时只补解析，不重新生成答案")
  void shouldOnlyBackfillMissingAnalysis() throws Exception {
    BoundedExecutor executor = executor(1, 5);
    InterviewQuestionDTO question = structuredAnswerQuestionWithoutAnalysis(0);
    PaperStructureService.BuiltStructure built = built(List.of(question));
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
        0, "补生成的解析", List.of("考点"), "评分规则"))));

    service(executor).prepare(PAPER_ID, built);

    assertThat(capturedKeys(built).get(0).analysis()).isEqualTo("补生成的解析");
    verify(invoker, atLeastOnce()).invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class));
  }

  @Test
  @DisplayName("正常批失败后只补缺失题，补跑按一题一批")
  void shouldRecoverOnlyMissingQuestionsAfterBatchFailure() throws Exception {
    BoundedExecutor executor = executor(2, 10);
    List<InterviewQuestionDTO> questions = List.of(
        choiceQuestion(0), choiceQuestion(1), choiceQuestion(2));
    PaperStructureService.BuiltStructure built = built(questions);
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        List<Integer> indexes = questionIndexes(prompt);
        if (indexes.size() > 1) {
          throw new BusinessException(
              ErrorCode.PAPER_EXTRACTION_FAILED, "正常解析批次失败");
        }
        int index = indexes.getFirst();
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            index, "补跑解析-" + index, List.of("考点"), "评分规则")));
      }
      return answers(prompt, "A");
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor, 2, 4, Duration.ofSeconds(60)).prepare(PAPER_ID, built);

    Map<Integer, AnswerKeyDTO> keys = capturedKeys(built);
    assertThat(keys.get(0).analysis()).isEqualTo("补跑解析-0");
    assertThat(keys.get(1).analysis()).isEqualTo("补跑解析-1");
    assertThat(keys.get(2).analysis()).isEqualTo("补跑解析-2");
    assertThat(prepared.warnings())
        .anyMatch(warning -> warning.contains("第 1、2 题解析未生成"));

    ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
    verify(invoker, atLeastOnce()).invokeWithoutSchemaValidation(
        any(), anyString(), promptCaptor.capture(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class));
    List<List<Integer>> analysisBatches = promptCaptor.getAllValues().stream()
        .filter(prompt -> prompt.contains("已确认答案："))
        .map(this::questionIndexes)
        .toList();
    assertThat(analysisBatches)
        .anySatisfy(indexes -> assertThat(indexes).containsExactly(0, 1));
    assertThat(analysisBatches.stream().filter(indexes -> indexes.size() == 1))
        .hasSize(3);
  }

  @Test
  @DisplayName("补跑次数达到上限时保留剩余缺题并给出提示")
  void shouldLimitRecoveryCallsAndWarnSkippedQuestions() throws Exception {
    BoundedExecutor executor = executor(2, 10);
    List<InterviewQuestionDTO> questions = List.of(
        choiceQuestion(0), choiceQuestion(1));
    PaperStructureService.BuiltStructure built = built(questions);
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        List<Integer> indexes = questionIndexes(prompt);
        if (indexes.size() > 1) {
          return new AnalysisBatchDTO(List.of());
        }
        int index = indexes.getFirst();
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            index, "补跑解析-" + index, List.of("考点"), "评分规则")));
      }
      return answers(prompt, "A");
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor, 2, 1, Duration.ofSeconds(60)).prepare(PAPER_ID, built);

    Map<Integer, AnswerKeyDTO> keys = capturedKeys(built);
    assertThat(keys.get(0).analysis()).isEqualTo("补跑解析-0");
    assertThat(keys.get(1).analysis()).contains("解析未生成");
    assertThat(prepared.warnings())
        .anyMatch(warning -> warning.contains("解析补跑预算用尽"));
  }

  @Test
  @DisplayName("补跑截止时间已过时不再发起模型调用")
  void shouldStopRecoveryWhenDeadlineHasPassed() throws Exception {
    BoundedExecutor executor = executor(2, 10);
    List<InterviewQuestionDTO> questions = List.of(choiceQuestion(0));
    PaperStructureService.BuiltStructure built = built(questions);
    AtomicInteger analysisCalls = new AtomicInteger();
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        analysisCalls.incrementAndGet();
        return new AnalysisBatchDTO(List.of());
      }
      return answers(prompt, "A");
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor, 1, 4, Duration.ofMillis(-1)).prepare(PAPER_ID, built);

    Map<Integer, AnswerKeyDTO> keys = capturedKeys(built);
    assertThat(analysisCalls.get()).isEqualTo(1);
    assertThat(keys.get(0).analysis()).contains("解析未生成");
    assertThat(prepared.warnings())
        .anyMatch(warning -> warning.contains("解析未生成"));
  }

  @Test
  @DisplayName("只给缺失题生成答案，结构已有答案不进入答案批次")
  void shouldGenerateOnlyMissingQuestion() throws Exception {
    BoundedExecutor executor = executor(2, 5);
    InterviewQuestionDTO existing = structuredAnswerQuestion(0);
    InterviewQuestionDTO missing = choiceQuestion(1);
    PaperStructureService.BuiltStructure built = built(List.of(existing, missing));
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        int questionIndex = firstQuestionIndex(prompt);
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            questionIndex, "解析-" + questionIndex, List.of("考点"), "评分规则")));
      }
      return answers(prompt, "A");
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor).prepare(PAPER_ID, built);

    Map<Integer, AnswerKeyDTO> keys = capturedKeys(built);
    assertThat(keys.get(0).source()).isEqualTo(AnswerKeyDTO.SOURCE_STRUCTURE);
    assertThat(keys.get(1).referenceAnswer()).isEqualTo("A");
    assertThat(prepared.warnings())
        .anyMatch(warning -> warning.contains("仅重新生成 1 道"));

    ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
    verify(invoker, atLeastOnce()).invokeWithoutSchemaValidation(
        any(), anyString(), promptCaptor.capture(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class));
    assertThat(promptCaptor.getAllValues())
        .filteredOn(prompt -> !prompt.contains("已确认答案："))
        .allSatisfy(prompt -> assertThat(prompt)
            .doesNotContain("[questionIndex=0]")
            .contains("[questionIndex=1]"));
  }

  @Test
  @DisplayName("主观题只有评分规则时按 RUBRIC_ONLY 通过，并由复核补全要点")
  void shouldPassRubricOnlySubjectiveQuestion() throws Exception {
    BoundedExecutor executor = executor(2, 5);
    InterviewQuestionDTO question = subjectiveQuestion(0, "暂缺", List.of("原有要点"), "按点给分");
    PaperStructureService.BuiltStructure built = built(List.of(question));
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            0, "主观题解析", List.of("复核要点"), "按点给分")));
      }
      if (prompt.contains("请为下列主观题补全评分依据")) {
        return new SubjectiveReviewBatchDTO(List.of(new SubjectiveReviewDTO(
            0, List.of("复核要点"), "按点给分", null)));
      }
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "主求解失败");
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor).prepare(PAPER_ID, built);

    AnswerKeyDTO key = capturedKeys(built).get(0);
    assertThat(key.answerMode()).isEqualTo(AnswerKeyDTO.ANSWER_MODE_RUBRIC_ONLY);
    assertThat(key.keyPoints()).containsExactly("复核要点");
    assertThat(key.scoringRubric()).isEqualTo("按点给分");
    assertThat(prepared.warnings())
        .anyMatch(warning -> warning.contains("主求解失败，已保留其他批次结果"));
  }

  @Test
  @DisplayName("主观题没有任何评分依据时转人工复核，不抛整卷失败")
  void shouldMarkSubjectiveQuestionWithoutBasisForManualReview() throws Exception {
    BoundedExecutor executor = executor(2, 5);
    InterviewQuestionDTO question = subjectiveQuestion(0, "暂缺", List.of(), null);
    PaperStructureService.BuiltStructure built = built(List.of(question));
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            0, "人工复核解析", List.of(), null)));
      }
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "答案模型失败");
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor).prepare(PAPER_ID, built);

    AnswerKeyDTO key = capturedKeys(built).get(0);
    assertThat(key.answerMode()).isEqualTo(AnswerKeyDTO.ANSWER_MODE_MANUAL_REVIEW);
    assertThat(key.referenceAnswer()).isEqualTo("暂缺");
    assertThat(prepared.warnings())
        .anyMatch(warning -> warning.contains("缺少评分依据，已转人工复核"));
  }

  @Test
  @DisplayName("圈号选项在单线程执行时也能完成答案一致性裁决")
  void shouldResolveCircledChoiceWhenSerial() throws Exception {
    BoundedExecutor executor = executor(1, 5);
    List<InterviewQuestionDTO> questions = List.of(circledChoiceQuestion(0));
    PaperStructureService.BuiltStructure built = built(questions);
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            0, "圈号选项解析", List.of("考点"), "评分规则")));
      }
      String answer = prompt.contains("这是独立复核") ? "④" : "D";
      return new AnswerBatchDTO(List.of(new GeneratedAnswerDTO(
          0, answer, List.of(answer), null, false, 90)));
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor).prepare(PAPER_ID, built);

    Map<Integer, AnswerKeyDTO> keys = capturedKeys(built);
    assertThat(keys.get(0).referenceAnswer()).isEqualTo("④");
    assertThat(keys.get(0).optionCorrectness())
        .containsEntry("①", false)
        .containsEntry("④", true);
    assertThat(prepared.warnings()).isEmpty();
  }

  @Test
  @DisplayName("独立复核失败时保留主答案并记录警告")
  void shouldKeepPrimaryAnswerWhenVerifyFails() throws Exception {
    BoundedExecutor executor = executor(2, 5);
    List<InterviewQuestionDTO> questions = List.of(choiceQuestion(0));
    PaperStructureService.BuiltStructure built = built(questions);
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            0, "主答案解析", List.of("考点"), "评分规则")));
      }
      if (prompt.contains("这是独立复核")) {
        throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "复核模型失败");
      }
      return answers(prompt, "A");
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor).prepare(PAPER_ID, built);

    Map<Integer, AnswerKeyDTO> keys = capturedKeys(built);
    assertThat(keys.get(0).referenceAnswer()).isEqualTo("A");
    assertThat(keys.get(0).analysis()).isEqualTo("主答案解析");
    assertThat(prepared.warnings())
        .anyMatch(warning -> warning.contains("独立复核失败，已保留主求解答案"))
        .anyMatch(warning -> warning.contains("缺少独立复核，已保留主答案"));
  }

  @Test
  @DisplayName("主求解失败时不取消已成功的复核批次")
  void shouldKeepVerifierAnswerWhenPrimaryFails() throws Exception {
    BoundedExecutor executor = executor(2, 5);
    List<InterviewQuestionDTO> questions = List.of(choiceQuestion(0));
    PaperStructureService.BuiltStructure built = built(questions);
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            0, "复核答案解析", List.of("考点"), "评分规则")));
      }
      if (prompt.contains("这是独立复核")) {
        return answers(prompt, "A");
      }
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "主求解模型失败");
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor).prepare(PAPER_ID, built);

    Map<Integer, AnswerKeyDTO> keys = capturedKeys(built);
    assertThat(keys.get(0).referenceAnswer()).isEqualTo("A");
    assertThat(prepared.warnings())
        .anyMatch(warning -> warning.contains("主求解失败，已保留其他批次结果"));
  }

  @Test
  @DisplayName("视觉准入拒绝时整题转人工复核，不抛整卷失败")
  void shouldMarkManualReviewWhenVisionBatchIsRejected() throws Exception {
    BoundedExecutor executor = executor(2, 5);
    List<InterviewQuestionDTO> questions = List.of(visualChoiceQuestion(0));
    PaperStructureService.BuiltStructure built = built(questions);
    PaperPageEntity page = page();
    when(pageRepository.findByPaperIdAndPageNo(PAPER_ID, 1))
        .thenReturn(Optional.of(page));
    when(storageService.downloadFile(page.getImageKey())).thenReturn(new byte[] {1, 2, 3});
    when(providerRegistry.getDefaultVisionChatClient()).thenReturn(chatClient);
    when(providerRegistry.getCurrentUserVisionProviderId()).thenReturn("test-provider");
    when(providerRegistry.getCurrentUserVisionModelName()).thenReturn("test-vision");
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeVisionOnceWithoutSchemaValidation(
        any(), anyString(), anyString(), anyList(), any(), any(), anyString(), anyString(),
        any(), any()
    )).thenThrow(new VisionRequestRejectedException("vision busy"));
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenReturn(new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
        0, "人工复核题目解析", List.of("考点"), "评分规则"))));

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor).prepare(PAPER_ID, built);

    Map<Integer, AnswerKeyDTO> keys = capturedKeys(built);
    assertThat(keys.get(0).answerMode()).isEqualTo(AnswerKeyDTO.ANSWER_MODE_MANUAL_REVIEW);
    assertThat(keys.get(0).referenceAnswer()).isEqualTo("暂缺");
    assertThat(prepared.warnings())
        .anyMatch(warning -> warning.contains("图片题调用被限流"))
        .anyMatch(warning -> warning.contains("已转人工复核"));
  }

  @Test
  @DisplayName("主答案与复核都缺候选时直接转人工复核，不触发“答案候选不能为空”")
  void shouldNotResolveWhenPrimaryAndVerifierHaveNoCandidate() throws Exception {
    BoundedExecutor executor = executor(2, 5);
    PaperStructureService.BuiltStructure built = built(List.of(choiceQuestion(0)));
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), any()))
        .thenReturn(built);
    when(invoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(BeanOutputConverter.class),
        eq(ErrorCode.PAPER_EXTRACTION_FAILED), anyString(), anyString(), any(Logger.class),
        any(Consumer.class)
    )).thenAnswer(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("已确认答案：")) {
        return new AnalysisBatchDTO(List.of(new GeneratedAnalysisDTO(
            0, "暂缺答案解析", List.of(), null)));
      }
      return new AnswerBatchDTO(List.of(
          new GeneratedAnswerDTO(0, "暂缺", List.of(), null, false, 90)));
    });

    AnswerKeyPreparationService.PreparedAnswerKeys prepared =
        service(executor).prepare(PAPER_ID, built);

    AnswerKeyDTO key = capturedKeys(built).get(0);
    assertThat(key.answerMode()).isEqualTo(AnswerKeyDTO.ANSWER_MODE_MANUAL_REVIEW);
    assertThat(key.referenceAnswer()).isEqualTo("暂缺");
    assertThat(prepared.warnings())
        .anyMatch(warning -> warning.contains("未生成确定答案，已转人工复核"));
  }

  private AnswerKeyPreparationService service(BoundedExecutor executor) throws Exception {
    return service(executor, 1, 4, Duration.ofSeconds(60));
  }

  private AnswerKeyPreparationService service(
      BoundedExecutor executor,
      int analysisBatchSize,
      int recoveryMaxCalls,
      Duration recoveryMaxWait
  ) throws Exception {
    PaperAnswerExecutorProperties properties = new PaperAnswerExecutorProperties();
    properties.setAnalysisBatchSize(analysisBatchSize);
    properties.setAnalysisRecoveryMaxCalls(recoveryMaxCalls);
    properties.setAnalysisRecoveryMaxWait(recoveryMaxWait);
    return new AnswerKeyPreparationService(
        providerRegistry, invoker, assetService, pageRepository, storageService,
        imageNormalizer, structureService, new AnswerConsistencyResolver(),
        new DefaultResourceLoader(), new AnswerBatchRunner(executor),
        new PaperAnalysisPacer(usageGate, properties), properties,
        mock(FreeModelConcurrencyGate.class), new AnalysisBatchScheduler(executor));
  }

  private BoundedExecutor executor(int poolSize, int queueCapacity) {
    BoundedExecutor executor = TestBoundedExecutor.create(poolSize, queueCapacity);
    executors.add(executor);
    return executor;
  }

  private PaperStructureService.BuiltStructure built(List<InterviewQuestionDTO> questions) {
    PaperTree tree = PaperTree.fromFlatQuestions(questions, "数学");
    return new PaperStructureService.BuiltStructure(
        "math", tree, questions, questions.size() * 5, List.of());
  }

  @SuppressWarnings("unchecked")
  private Map<Integer, AnswerKeyDTO> capturedKeys(
      PaperStructureService.BuiltStructure built
  ) {
    ArgumentCaptor<Map<Integer, AnswerKeyDTO>> captor = ArgumentCaptor.forClass(Map.class);
    verify(structureService, atLeastOnce())
        .applyAndStoreAnswerKeys(eq(PAPER_ID), eq(built), captor.capture());
    return captor.getAllValues().getLast();
  }

  private AnswerBatchDTO answers(String prompt, String answer) {
    List<GeneratedAnswerDTO> generated = QUESTION_INDEX.matcher(prompt).results()
        .map(match -> Integer.parseInt(match.group(1)))
        .distinct()
        .map(index -> new GeneratedAnswerDTO(
            index, answer, List.of(answer), null, false, 90))
        .toList();
    return new AnswerBatchDTO(generated);
  }

  private int firstQuestionIndex(String prompt) {
    Matcher matcher = QUESTION_INDEX.matcher(prompt);
    if (!matcher.find()) {
      throw new IllegalStateException("提示词中没有 questionIndex");
    }
    return Integer.parseInt(matcher.group(1));
  }

  private List<Integer> questionIndexes(String prompt) {
    return QUESTION_INDEX.matcher(prompt).results()
        .map(match -> Integer.parseInt(match.group(1)))
        .toList();
  }

  private InterviewQuestionDTO choiceQuestion(int index) {
    return question(index, "在三角形中求概率");
  }

  private InterviewQuestionDTO circledChoiceQuestion(int index) {
    return new InterviewQuestionDTO(
        index,
        "Which of the following is true?",
        "english",
        "阅读",
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
        3,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(
            new QuestionOption("①", "It is native to lowlands and marshes."),
            new QuestionOption("②", "It is more closely related to the mule deer."),
            new QuestionOption("③", "It has replaced the black-tailed deer."),
            new QuestionOption("④", "It no longer lives in a particular habitat.")),
        null, 2, 2, null, null, null, false);
  }

  private InterviewQuestionDTO visualChoiceQuestion(int index) {
    return question(index, "如图，在三角形中求概率");
  }

  private InterviewQuestionDTO structuredAnswerQuestion(int index) {
    return question(index, "在三角形中求概率").withAnswerKey(new AnswerKeyDTO(
        index,
        "C",
        Map.of("A", false, "B", false, "C", true, "D", false),
        "已有解析",
        List.of(),
        null,
        null,
        false,
        100,
        AnswerKeyDTO.SOURCE_STRUCTURE
    ));
  }

  private InterviewQuestionDTO structuredAnswerQuestionWithoutAnalysis(int index) {
    return question(index, "在三角形中求概率").withAnswerKey(new AnswerKeyDTO(
        index,
        "C",
        Map.of("A", false, "B", false, "C", true, "D", false),
        null,
        List.of(),
        null,
        null,
        false,
        100,
        AnswerKeyDTO.SOURCE_STRUCTURE
    ));
  }

  private InterviewQuestionDTO subjectiveQuestion(
      int index,
      String referenceAnswer,
      List<String> keyPoints,
      String scoringRubric
  ) {
    return new InterviewQuestionDTO(
        index,
        "结合材料分析作者的观点。",
        "chinese",
        "阅读理解",
        null,
        null,
        null,
        null,
        false,
        null,
        referenceAnswer,
        keyPoints,
        scoringRubric,
        null,
        10,
        InterviewQuestionDTO.TYPE_SHORT_ANSWER,
        null,
        null,
        1,
        1,
        null,
        null,
        null,
        false);
  }

  private InterviewQuestionDTO question(int index, String prompt) {
    return new InterviewQuestionDTO(
        index, prompt, "math", "几何", null, "C", null, null,
        false, null, "暂缺", List.of(), null, null, 5,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(
            new QuestionOption("A", "(6-π)/6"),
            new QuestionOption("B", "(12-π)/12"),
            new QuestionOption("C", "π/6"),
            new QuestionOption("D", "π/12")),
        null, 1, 1, null, null, null, false);
  }

  private PaperPageEntity page() {
    PaperPageEntity page = new PaperPageEntity();
    page.setPaperId(PAPER_ID);
    page.setPageNo(1);
    page.setImageKey("papers/1/pages/1.jpg");
    return page;
  }
}
