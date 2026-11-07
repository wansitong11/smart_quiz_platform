package interview.guide.modules.resume.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.PageGeometryDTO;
import interview.guide.modules.resume.model.PageOcrDTO;
import interview.guide.modules.resume.model.PageRecognition;
import interview.guide.modules.resume.model.PaperPageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("单页两阶段视觉识别")
class PaperPageVisionRecognizerTest {

  private StructuredOutputInvoker structuredOutputInvoker;
  private PaperRecognitionExecutorProperties properties;
  private ChatClient chatClient;
  private PaperPageVisionRecognizer recognizer;

  @BeforeEach
  void setUp() throws IOException {
    structuredOutputInvoker = mock(StructuredOutputInvoker.class);
    properties = new PaperRecognitionExecutorProperties();
    properties.setTwoStageRecognitionEnabled(true);
    properties.setMaxVisionCallsPerPage(3);
    chatClient = mock(ChatClient.class);
    recognizer = new PaperPageVisionRecognizer(
        structuredOutputInvoker,
        properties,
        new PageRecognitionValidator(),
        new ObjectMapper(),
        new DefaultResourceLoader()
    );
  }

  @Test
  @DisplayName("无图页面只调用 OCR")
  void textOnlyPageUsesOcrOnly() {
    String text = "1. " + "这是完整转写的正文内容。".repeat(12);
    answerVision(invocation -> {
      assertOcrPrompt(invocation.getArgument(2));
      return new PageOcrDTO(1, text, false, List.of(), "OCR 正常");
    });

    PageRecognition result = recognizer.recognize(page(), 1, new byte[]{1}, chatClient);

    assertThat(result.text()).isEqualTo(text);
    assertThat(result.lines()).isEmpty();
    assertThat(result.assets()).isEmpty();
    assertThat(result.hasFigure()).isFalse();
    assertThat(result.notes()).isEqualTo("OCR 正常");
  }

  @Test
  @DisplayName("有图页面调用 OCR 和资源几何并合并锚点")
  void visualPageUsesOcrAndGeometry() {
    String text = "1. 如图，求阴影部分面积。";
    PageRecognition.PageLine line = new PageRecognition.PageLine(
        "1. 如图，求阴影部分面积。", new double[]{0.1, 0.2, 0.8, 0.1});
    PageRecognition.PageAsset asset = new PageRecognition.PageAsset(
        "FIGURE", "STEM", "1. 如图，求阴影部分面积。",
        new double[]{0.2, 0.35, 0.4, 0.3}, null, null, List.of());
    answerVision(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("请只转写")) {
        return new PageOcrDTO(1, text, true, List.of("1. 如图，求阴影部分面积。"), "OCR 正常");
      }
      assertGeometryPrompt(prompt, text);
      return new PageGeometryDTO(
          List.of(line), List.of(asset), List.of("1. 如图，求阴影部分面积。"), "几何正常");
    });

    PageRecognition result = recognizer.recognize(page(), 1, new byte[]{1}, chatClient);

    assertThat(result.text()).isEqualTo(text);
    assertThat(result.lines()).containsExactly(line);
    assertThat(result.assets()).containsExactly(asset);
    assertThat(result.hasFigure()).isTrue();
    assertThat(result.stemFigureAnchors()).containsExactly("1. 如图，求阴影部分面积。");
    assertThat(result.notes()).isEqualTo("OCR 正常；几何正常");
    PageRecognitionValidator validator = new PageRecognitionValidator();
    assertThat(validator.hasMissingVisualAssets(text, true, true)).isFalse();
    assertThat(validator.hasMissingDeclaredStemFigureAssets(
        result.stemFigureAnchors(), result.assets())).isFalse();
  }

  @Test
  @DisplayName("资源几何失败时不重新 OCR")
  void geometryFailureDoesNotReissueOcr() {
    AtomicInteger ocrCalls = new AtomicInteger();
    AtomicInteger geometryCalls = new AtomicInteger();
    String text = "1. 如图，求阴影部分面积。";
    answerVision(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("请只转写")) {
        ocrCalls.incrementAndGet();
        return new PageOcrDTO(1, text, true, List.of(), null);
      }
      geometryCalls.incrementAndGet();
      throw new BusinessException(
          ErrorCode.PAPER_RECOGNITION_FAILED, "第 1 页资源定位失败");
    });

    assertThatThrownBy(() ->
        recognizer.recognize(page(), 1, new byte[]{1}, chatClient))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("资源定位失败");

    assertThat(ocrCalls).hasValue(1);
    assertThat(geometryCalls).hasValue(1);
  }

  @Test
  @DisplayName("OCR 重试仍为几何阶段保留一次预算")
  void ocrRetryKeepsGeometryBudget() throws IOException {
    AtomicInteger ocrCalls = new AtomicInteger();
    AtomicInteger geometryCalls = new AtomicInteger();
    String completeText = "1. 如图，求阴影部分面积。";
    PageRecognition.PageLine line = new PageRecognition.PageLine(
        completeText, new double[]{0.1, 0.2, 0.8, 0.1});
    PageRecognition.PageAsset asset = new PageRecognition.PageAsset(
        "FIGURE", "STEM", completeText,
        new double[]{0.2, 0.35, 0.4, 0.3}, null, null, List.of());
    answerVision(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("请只转写")) {
        int call = ocrCalls.incrementAndGet();
        if (call == 1) {
          return new PageOcrDTO(1, "太短", false, List.of(), null);
        }
        return new PageOcrDTO(1, completeText, true, List.of(completeText), null);
      }
      geometryCalls.incrementAndGet();
      return new PageGeometryDTO(
          List.of(line), List.of(asset), List.of(completeText), null);
    });

    PageRecognition result = recognizer.recognize(
        page(), 1, denseTextPage(), chatClient);

    assertThat(ocrCalls).hasValue(2);
    assertThat(geometryCalls).hasValue(1);
    assertThat(result.text()).isEqualTo(completeText);
    assertThat(result.assets()).containsExactly(asset);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void answerVision(Answer<Object> answer) {
    when(structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
        any(),
        anyString(),
        anyString(),
        anyList(),
        any(BeanOutputConverter.class),
        any(),
        anyString(),
        anyString(),
        any(),
        any(Consumer.class)
    )).thenAnswer(answer);
  }

  private void assertOcrPrompt(String prompt) {
    assertThat(prompt)
        .contains("请只转写")
        .doesNotContain("请只定位");
  }

  private void assertGeometryPrompt(String prompt, String ocrText) {
    assertThat(prompt)
        .contains("请只定位")
        .contains(ocrText)
        .doesNotContain("请只转写");
  }

  private PaperPageEntity page() {
    PaperPageEntity page = new PaperPageEntity();
    page.setPaperId(1L);
    page.setPageNo(1);
    return page;
  }

  private byte[] denseTextPage() throws IOException {
    BufferedImage image = new BufferedImage(240, 360, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.setColor(Color.BLACK);
    for (int i = 0; i < 10; i++) {
      graphics.fillRect(30, 30 + i * 28, 180, 8);
    }
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", output);
    return output.toByteArray();
  }
}
