package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.TestBoundedExecutor;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.aicost.service.AiUsageGate;
import interview.guide.modules.aicost.service.FreeModelConcurrencyGate;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.service.EvaluationContextBuilder;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.repository.PaperPageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("试卷批改资源代次契约")
class PaperGradingAssetContractTest {

  private static final Long PAPER_ID = 11L;
  private static final String CURRENT_ATTEMPT = "attempt-current";
  private static final String OLD_ATTEMPT = "attempt-old";
  private static final String CURRENT_ROW = "p1a4@" + CURRENT_ATTEMPT;
  private static final String OLD_ROW = "p1a4@" + OLD_ATTEMPT;

  @Mock
  private LlmProviderRegistry providerRegistry;
  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;
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

  @Test
  @DisplayName("答案键和批改只使用当前代次的四个选项切片与整页图")
  void currentGenerationSurvivesBindingAnswerKeyAndGrading() throws Exception {
    PaperAssetService assetService = org.mockito.Mockito.mock(PaperAssetService.class);
    PaperAssetBindingService bindingService =
        new PaperAssetBindingService(assetService, new OptionRowSplitter());
    PaperAssetEntity currentRow = asset(CURRENT_ROW, "OPTION_ROW", CURRENT_ATTEMPT);
    currentRow.setOptionCount(4);
    when(assetService.download(currentRow)).thenReturn(optionRowImage(4));
    when(assetService.saveTile(anyLong(), anyInt(), anyString(), anyString(),
        any(byte[].class), anyInt(), anyInt(), anyString())).thenReturn(new PaperAssetEntity());

    PaperAssetBindingService.BindingResult bound = bindingService.bind(
        PAPER_ID,
        tree(boundQuestion(List.of())).root(),
        Map.of(CURRENT_ROW, optionRow(CURRENT_ROW)),
        List.of(currentRow, asset(OLD_ROW, "OPTION_ROW", OLD_ATTEMPT))
    );

    List<String> currentTiles = bound.root().collectQuestions().getFirst().question().options()
        .stream().map(QuestionOption::assetId).toList();
    assertThat(currentTiles).containsExactly(
        CURRENT_ROW + ":A", CURRENT_ROW + ":B", CURRENT_ROW + ":C", CURRENT_ROW + ":D");

    PaperTree currentTree = PaperTree.recalculate(
        new PaperTree(PaperTree.CURRENT_VERSION, bound.root()));
    Map<Integer, List<String>> gradingAssets =
        EvaluationContextBuilder.collectQuestionAssetIds(currentTree);
    assertThat(gradingAssets.get(0)).containsExactlyElementsOf(currentTiles);

    currentTiles.forEach(key -> when(assetService.find(PAPER_ID, key))
        .thenReturn(Optional.of(asset(key, "OPTION_ITEM", CURRENT_ATTEMPT))));
    when(assetService.download(any(PaperAssetEntity.class))).thenReturn(new byte[] {1, 2, 3});
    when(imageNormalizer.prepareForVision(any())).thenReturn(new byte[] {4, 5, 6});
    PaperPageEntity page = page();
    when(pageRepository.findByPaperIdAndPageNo(PAPER_ID, 1)).thenReturn(Optional.of(page));
    when(storageService.downloadFile(page.getImageKey())).thenReturn(new byte[] {7, 8, 9});
    when(providerRegistry.getDefaultVisionChatClient()).thenReturn(chatClient);
    when(providerRegistry.getPlainChatClient()).thenReturn(chatClient);
    when(providerRegistry.getCurrentUserVisionProviderId()).thenReturn("test-provider");
    when(providerRegistry.getCurrentUserVisionModelName()).thenReturn("test-vision");
    when(structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
        any(), anyString(), anyString(), anyList(), any(), any(), anyString(), anyString(),
        any(), any()))
        .thenReturn(new AnswerKeyPreparationService.AnswerBatchDTO(List.of(
            new AnswerKeyPreparationService.GeneratedAnswerDTO(
                0, "A", List.of("A"), "四个选项", false, 95))));
    when(structuredOutputInvoker.invokeWithoutSchemaValidation(
        any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any()))
        .thenReturn(new AnswerKeyPreparationService.AnalysisBatchDTO(List.of(
            new AnswerKeyPreparationService.GeneratedAnalysisDTO(
                0, "选项 A 与题干一致。", List.of("选项关系"), "按选项正确性给分"))));
    PaperStructureService.BuiltStructure built = new PaperStructureService.BuiltStructure(
        "math", currentTree, currentTree.flattenQuestions(), 3, List.of());
    when(structureService.applyAndStoreAnswerKeys(eq(PAPER_ID), any(), any())).thenReturn(built);

    BoundedExecutor answerExecutor = TestBoundedExecutor.create(2, 20);
    PaperAnswerExecutorProperties answerProperties = new PaperAnswerExecutorProperties();
    AnswerKeyPreparationService answerKeyService = new AnswerKeyPreparationService(
        providerRegistry,
        structuredOutputInvoker,
        assetService,
        pageRepository,
        storageService,
        imageNormalizer,
        structureService,
        new AnswerConsistencyResolver(),
        new DefaultResourceLoader(),
        new AnswerBatchRunner(answerExecutor),
        new PaperAnalysisPacer(org.mockito.Mockito.mock(AiUsageGate.class), answerProperties),
        answerProperties,
        org.mockito.Mockito.mock(FreeModelConcurrencyGate.class),
        new AnalysisBatchScheduler(answerExecutor)
    );
    try {
      answerKeyService.prepare(PAPER_ID, built);
    } finally {
      answerExecutor.shutdown();
    }

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Media>> mediaCaptor = ArgumentCaptor.forClass(List.class);
    verify(structuredOutputInvoker, org.mockito.Mockito.atLeastOnce())
        .invokeVisionOnceWithoutSchemaValidation(
            any(), anyString(), anyString(), mediaCaptor.capture(), any(), any(), anyString(),
            anyString(), any(), any());
    assertThat(mediaCaptor.getAllValues()).allSatisfy(media ->
        assertThat(media).hasSize(5));
    verify(assetService, never()).find(PAPER_ID, OLD_ROW);
    verify(assetService, never()).find(PAPER_ID, OLD_ROW + ":A");
  }

  private PaperTree tree(InterviewQuestionDTO question) {
    PaperNode questionNode = new PaperNode(
        "q1", PaperNode.TYPE_QUESTION, 1, 0, true, null, null, null,
        new PaperNode.PaperMeta(3, 1, null, List.of(), 1, 2),
        0, question, List.of());
    return PaperTree.recalculate(new PaperTree(
        PaperTree.CURRENT_VERSION,
        PaperNode.paper("n0", "试卷", null, List.of(questionNode))
    ));
  }

  private InterviewQuestionDTO boundQuestion(List<QuestionOption> options) {
    return new InterviewQuestionDTO(
        0, "看图选择", "math", "几何", null, null, null, null, false, null,
        null, List.of(), null, null, 3, InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        options, null, 1, 1, null, null, null, false);
  }

  private PaperAssetBinding optionRow(String key) {
    return new PaperAssetBinding(key, 1, 1, "OPTION_ROW", "OPTIONS",
        new double[] {0.1, 0.2, 0.8, 0.1});
  }

  private PaperAssetEntity asset(String key, String kind, String attemptId) {
    PaperAssetEntity asset = new PaperAssetEntity();
    asset.setPaperId(PAPER_ID);
    asset.setPageNo(1);
    asset.setAssetKey(key);
    asset.setSourceAttemptId(attemptId);
    asset.setKind(kind);
    asset.setRole("OPTIONS");
    asset.setStorageKey("papers/11/assets/" + key + ".jpg");
    return asset;
  }

  private PaperPageEntity page() {
    PaperPageEntity page = new PaperPageEntity();
    page.setPaperId(PAPER_ID);
    page.setPageNo(1);
    page.setImageKey("papers/11/pages/1.jpg");
    return page;
  }

  private byte[] optionRowImage(int optionCount) throws Exception {
    int width = 400;
    int height = 80;
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, width, height);
    graphics.setColor(Color.BLACK);
    int segment = width / optionCount;
    for (int i = 0; i < optionCount; i++) {
      graphics.fillRect(i * segment + 20, 20, segment - 40, 40);
    }
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }
}
