package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.service.VisualQuestionEvaluationService.VisualGradeDTO;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.service.PageImageNormalizer;
import interview.guide.modules.resume.service.PaperAssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("图形主观题批改图片输入")
class VisualQuestionEvaluationServiceTest {

  @Mock
  private LlmProviderRegistry providerRegistry;
  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;
  @Mock
  private PaperAssetService assetService;
  @Mock
  private PaperPageRepository pageRepository;
  @Mock
  private FileStorageService storageService;
  @Mock
  private PageImageNormalizer imageNormalizer;
  @Mock
  private ChatClient chatClient;

  private VisualQuestionEvaluationService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new VisualQuestionEvaluationService(
        providerRegistry,
        structuredOutputInvoker,
        assetService,
        pageRepository,
        storageService,
        imageNormalizer,
        new DefaultResourceLoader()
    );
  }

  @Test
  @DisplayName("图形题批改同时上传题目裁图和整页兜底图")
  void shouldPassAssetAndFullPageToVisionModel() {
    PaperAssetEntity asset = asset();
    PaperPageEntity page = page();
    when(providerRegistry.getDefaultVisionChatClient()).thenReturn(chatClient);
    when(assetService.find(16L, "p1a1")).thenReturn(Optional.of(asset));
    when(assetService.download(asset)).thenReturn(new byte[] {1, 2, 3});
    when(imageNormalizer.prepareForVision(any())).thenReturn(new byte[] {4, 5, 6});
    when(pageRepository.findByPaperIdAndPageNo(16L, 1)).thenReturn(Optional.of(page));
    when(storageService.downloadFile("papers/16/pages/1.jpg")).thenReturn(new byte[] {7, 8, 9});
    stubInvoker();

    service.grade(16L, visualQuestion(), List.of("p1a1"));

    assertThat(capturedMedia()).hasSize(2);
  }

  @Test
  @DisplayName("题目裁图缺失时仍上传整页原图")
  void shouldFallbackToFullPageWhenAssetIsMissing() {
    PaperPageEntity page = page();
    when(providerRegistry.getDefaultVisionChatClient()).thenReturn(chatClient);
    when(pageRepository.findByPaperIdAndPageNo(16L, 1)).thenReturn(Optional.of(page));
    when(storageService.downloadFile("papers/16/pages/1.jpg")).thenReturn(new byte[] {7, 8, 9});
    stubInvoker();

    service.grade(16L, visualQuestion(), List.of());

    assertThat(capturedMedia()).hasSize(1);
  }

  private void stubInvoker() {
    when(structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
        eq(chatClient),
        anyString(),
        anyString(),
        anyList(),
        any(),
        eq(ErrorCode.INTERVIEW_EVALUATION_FAILED),
        anyString(),
        anyString(),
        any()
    )).thenReturn(new VisualGradeDTO(2, 80, "图形关系正确。", false, 90));
  }

  private List<Media> capturedMedia() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Media>> captor = ArgumentCaptor.forClass(List.class);
    verify(structuredOutputInvoker).invokeVisionOnceWithoutSchemaValidation(
        eq(chatClient),
        anyString(),
        anyString(),
        captor.capture(),
        any(),
        eq(ErrorCode.INTERVIEW_EVALUATION_FAILED),
        anyString(),
        anyString(),
        any()
    );
    return captor.getValue();
  }

  private InterviewQuestionDTO visualQuestion() {
    return new InterviewQuestionDTO(
        2,
        "在图中补全正方体展开图。",
        "math",
        "几何",
        null,
        "延长线段 AB。",
        null,
        null,
        false,
        null,
        "补全后的展开图应包含六个正方形。",
        List.of("六个面完整"),
        null,
        null,
        5,
        InterviewQuestionDTO.TYPE_SHORT_ANSWER,
        null,
        null,
        1,
        1,
        "两个相邻正方形",
        "VISION",
        90,
        true
    );
  }

  private PaperAssetEntity asset() {
    PaperAssetEntity asset = new PaperAssetEntity();
    asset.setPaperId(16L);
    asset.setPageNo(1);
    asset.setAssetKey("p1a1");
    asset.setKind("FIGURE");
    asset.setRole("STEM");
    asset.setStorageKey("papers/16/assets/p1a1.jpg");
    return asset;
  }

  private PaperPageEntity page() {
    PaperPageEntity page = new PaperPageEntity();
    page.setPaperId(16L);
    page.setPageNo(1);
    page.setImageKey("papers/16/pages/1.jpg");
    return page;
  }
}
