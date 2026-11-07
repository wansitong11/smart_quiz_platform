package interview.guide.modules.resume.service;

import com.openai.errors.OpenAIIoException;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.PageOcrDTO;
import interview.guide.modules.resume.model.PageRecognition;
import interview.guide.modules.resume.model.PageRecognitionOutput;
import interview.guide.modules.resume.model.PaperPageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import tools.jackson.databind.exc.InvalidFormatException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
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

@DisplayName("单阶段视觉识别失败反馈")
class PaperPageVisionRecognizerOneStageTest {

  private static final String VALID_TEXT =
      "1. " + "这是完整转写的正文内容。".repeat(12);

  private StructuredOutputInvoker structuredOutputInvoker;
  private PaperRecognitionExecutorProperties properties;
  private ChatClient chatClient;
  private PaperPageVisionRecognizer recognizer;

  @BeforeEach
  void setUp() throws IOException {
    structuredOutputInvoker = mock(StructuredOutputInvoker.class);
    properties = new PaperRecognitionExecutorProperties();
    properties.setTwoStageRecognitionEnabled(false);
    properties.setMaxVisionCallsPerPage(3);
    properties.setInvalidOutputRetryThreshold(2);
    properties.setInvalidOutputFallbackEnabled(true);
    properties.setInvalidOutputFallbackMaxCalls(2);
    properties.setOutputTruncatedRetryMaxCount(1);
    properties.setTransportRetryEnabled(true);
    properties.setTransportRetryMaxCount(1);
    properties.setTransportRetryBackoffBase(Duration.ZERO);
    properties.setFeedbackMaxChars(200);
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
  @DisplayName("结构错误连续两次后带原因兜底到纯文字 OCR")
  void invalidOutputFallsBackToTextOnlyOcr() {
    List<String> prompts = new ArrayList<>();
    AtomicInteger visionCalls = new AtomicInteger();
    answerVision(invocation -> {
      String prompt = invocation.getArgument(2);
      prompts.add(prompt);
      if (prompt.contains("请只转写")) {
        return new PageOcrDTO(1, VALID_TEXT, false, List.of(), "兜底 OCR");
      }
      visionCalls.incrementAndGet();
      throw invalidOutputFailure();
    });

    PageRecognition result = recognizer.recognize(page(), 1, new byte[]{1}, chatClient);

    assertThat(visionCalls).hasValue(2);
    assertThat(prompts).hasSize(3);
    assertThat(prompts.get(0)).doesNotContain("lines[8].bbox[3]");
    assertThat(prompts.get(1))
        .contains("lines[8].bbox[3]")
        .contains("bbox");
    assertThat(prompts.get(2)).contains("请只转写");
    assertThat(result.text()).isEqualTo(VALID_TEXT);
    assertThat(result.hasFigure()).isFalse();
    assertThat(result.notes()).isEqualTo("兜底 OCR");
  }

  @Test
  @DisplayName("传输失败退避后重试一次并成功")
  void transportFailureRetriesOnceThenSucceeds() {
    AtomicInteger visionCalls = new AtomicInteger();
    answerVision(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("请只转写")) {
        return new PageOcrDTO(1, "不应进入兜底", false, List.of(), null);
      }
      if (visionCalls.incrementAndGet() == 1) {
        throw transportFailure();
      }
      return recognition(VALID_TEXT);
    });

    PageRecognition result = recognizer.recognize(page(), 1, new byte[]{1}, chatClient);

    assertThat(visionCalls).hasValue(2);
    assertThat(result.text()).isEqualTo(VALID_TEXT);
  }

  @Test
  @DisplayName("传输失败重试一次后仍失败即停止")
  void transportFailureStopsAfterOneRetry() {
    AtomicInteger visionCalls = new AtomicInteger();
    answerVision(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("请只转写")) {
        return new PageOcrDTO(1, "不应进入兜底", false, List.of(), null);
      }
      visionCalls.incrementAndGet();
      throw transportFailure();
    });

    assertThatThrownBy(() -> recognizer.recognize(page(), 1, new byte[]{1}, chatClient))
        .isInstanceOf(BusinessException.class);
    assertThat(visionCalls).hasValue(2);
  }

  @Test
  @DisplayName("lines 为空但页面有可见文本时重试")
  void emptyLinesRetriesForVisibleText() throws IOException {
    AtomicInteger visionCalls = new AtomicInteger();
    answerVision(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("请只转写")) {
        return new PageOcrDTO(1, "不应进入兜底", false, List.of(), null);
      }
      int call = visionCalls.incrementAndGet();
      if (call == 1) {
        return new PageRecognitionOutput(
            1, List.of(), List.of(), false, List.of(), null);
      }
      return recognition("可见文本");
    });

    PageRecognition result = recognizer.recognize(
        page(), 1, sparseTextPage(), chatClient);

    assertThat(visionCalls).hasValue(2);
    assertThat(result.text()).isEqualTo("可见文本");
  }

  @Test
  @DisplayName("加点文字没有标记时只提示人工校对，不再重抽整页")
  void missingMarkupDoesNotRetryPage() {
    AtomicInteger visionCalls = new AtomicInteger();
    String text = "1. 给下列加点字选择正确读音 " + "这是完整转写的正文内容。".repeat(10);
    answerVision(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("请只转写")) {
        return new PageOcrDTO(1, "不应进入兜底", false, List.of(), null);
      }
      visionCalls.incrementAndGet();
      return recognition(text);
    });

    PageRecognition result = recognizer.recognize(page(), 1, new byte[]{1}, chatClient);

    assertThat(visionCalls).hasValue(1);
    assertThat(result.text()).contains("加点字");
  }

  @Test
  @DisplayName("输出截断只做一次定向重试并成功")
  void truncatedOutputRetriesOnceWithTargetedPrompt() {
    AtomicInteger visionCalls = new AtomicInteger();
    List<String> prompts = new ArrayList<>();
    answerVision(invocation -> {
      String prompt = invocation.getArgument(2);
      prompts.add(prompt);
      if (visionCalls.incrementAndGet() == 1) {
        throw truncatedOutputFailure();
      }
      return recognition(VALID_TEXT);
    });

    PageRecognition result = recognizer.recognize(page(), 1, new byte[]{1}, chatClient);

    assertThat(visionCalls).hasValue(2);
    assertThat(prompts.get(1))
        .contains("输出在 JSON 完整闭合前被截断")
        .contains("只返回 lines");
    assertThat(result.text()).isEqualTo(VALID_TEXT);
  }

  @Test
  @DisplayName("连续输出截断时重试一次后停止")
  void repeatedTruncationStopsAfterOneRetry() {
    properties.setInvalidOutputFallbackEnabled(false);
    AtomicInteger visionCalls = new AtomicInteger();
    answerVision(invocation -> {
      visionCalls.incrementAndGet();
      throw truncatedOutputFailure();
    });

    assertThatThrownBy(() -> recognizer.recognize(page(), 1, new byte[]{1}, chatClient))
        .isInstanceOf(BusinessException.class);
    assertThat(visionCalls).hasValue(2);
  }

  @Test
  @DisplayName("连续输出截断后转入两阶段 OCR 兜底")
  void repeatedTruncationFallsBackToOcr() {
    AtomicInteger oneStageCalls = new AtomicInteger();
    AtomicInteger ocrCalls = new AtomicInteger();
    answerVision(invocation -> {
      String prompt = invocation.getArgument(2);
      if (prompt.contains("请只转写")) {
        ocrCalls.incrementAndGet();
        return new PageOcrDTO(1, VALID_TEXT, false, List.of(), "兜底 OCR");
      }
      oneStageCalls.incrementAndGet();
      throw truncatedOutputFailure();
    });

    PageRecognition result = recognizer.recognize(page(), 1, new byte[]{1}, chatClient);

    assertThat(oneStageCalls).hasValue(2);
    assertThat(ocrCalls).hasValue(1);
    assertThat(result.text()).isEqualTo(VALID_TEXT);
    assertThat(result.notes()).isEqualTo("兜底 OCR");
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

  private PageRecognitionOutput recognition(String text) {
    PageRecognition.PageLine line = new PageRecognition.PageLine(
        text, new double[]{0.1, 0.1, 0.8, 0.8});
    return new PageRecognitionOutput(
        1, List.of(line), List.of(), false, List.of(), null);
  }

  private BusinessException invalidOutputFailure() {
    return new BusinessException(
        ErrorCode.PAPER_RECOGNITION_FAILED,
        "第 1 页识别失败：请稍后重试",
        invalidFormatException());
  }

  private BusinessException transportFailure() {
    return new BusinessException(
        ErrorCode.PAPER_RECOGNITION_FAILED,
        "第 1 页识别失败：AI 服务连接失败或响应超时，请稍后重试",
        new OpenAIIoException("timeout"));
  }

  private BusinessException truncatedOutputFailure() {
    return new BusinessException(
        ErrorCode.PAPER_RECOGNITION_FAILED,
        "第 1 页识别失败：请稍后重试",
        mock(UnexpectedEndOfInputException.class));
  }

  private InvalidFormatException invalidFormatException() {
    BeanOutputConverter<PageRecognitionOutput> converter =
        new BeanOutputConverter<>(PageRecognitionOutput.class);
    try {
      converter.convert("""
          {"pageNo":1,
           "lines":[
            {"text":"1","bbox":[0.1,0.05,0.8,0.08]},
            {"text":"2","bbox":[0.1,0.13,0.8,0.08]},
            {"text":"3","bbox":[0.1,0.21,0.8,0.08]},
            {"text":"4","bbox":[0.1,0.29,0.8,0.08]},
            {"text":"5","bbox":[0.1,0.37,0.8,0.08]},
            {"text":"6","bbox":[0.1,0.45,0.8,0.08]},
            {"text":"7","bbox":[0.1,0.53,0.8,0.08]},
            {"text":"8","bbox":[0.1,0.61,0.8,0.08]},
            {"text":"9","bbox":[0.1,0.61,0.8,"bbox"]}],
           "assets":[],"hasFigure":false,"stemFigureAnchors":[],"notes":""}
          """);
    } catch (InvalidFormatException e) {
      return e;
    }
    throw new IllegalStateException("fixture 应当触发 InvalidFormatException");
  }

  private PaperPageEntity page() {
    PaperPageEntity page = new PaperPageEntity();
    page.setPaperId(1L);
    page.setPageNo(1);
    return page;
  }

  private byte[] sparseTextPage() throws IOException {
    BufferedImage image = new BufferedImage(240, 360, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.setColor(Color.BLACK);
    graphics.fillRect(30, 40, 180, 8);
    graphics.fillRect(30, 200, 180, 8);
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", output);
    return output.toByteArray();
  }
}
