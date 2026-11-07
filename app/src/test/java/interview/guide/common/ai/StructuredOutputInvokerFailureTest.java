package interview.guide.common.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.core.http.Headers;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.service.BlockTreeDTO;
import interview.guide.modules.resume.model.PageRecognition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("结构化输出失败文案")
class StructuredOutputInvokerFailureTest {

  private static final String VALID_BLOCK_TREE_JSON = """
      {
        "subjectKey": "math",
        "totalScore": 10,
        "nodes": [{"type": "QUESTION", "startLine": 1, "endLine": 2}],
        "warnings": []
      }
      """;

  private static final String EMPTY_BLOCK_TREE_JSON = """
      {"subjectKey":null,"totalScore":null,"nodes":null,"warnings":null}
      """;

  private final Logger invokerLogger =
      (Logger) LoggerFactory.getLogger(StructuredOutputInvoker.class);
  private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

  @BeforeEach
  void attachLogAppender() {
    logAppender.start();
    invokerLogger.addAppender(logAppender);
  }

  @AfterEach
  void detachLogAppender() {
    invokerLogger.detachAppender(logAppender);
  }

  @Test
  @DisplayName("连接异常映射为固定安全文案，不回显原始 message")
  void mapsTransportFailureToSafeMessage() {
    RuntimeException cause = new OpenAIIoException(
        "prompt=请转写用户试卷内容，requestBody=marker-7f3a");

    String message = StructuredOutputInvoker.failureMessage("第 3 页识别失败：", cause);

    assertThat(message)
        .isEqualTo("第 3 页识别失败：AI 服务连接失败或响应超时，请稍后重试")
        .doesNotContain("prompt", "requestBody", "marker-7f3a", "用户试卷内容");
  }

  @Test
  @DisplayName("普通异常保留固定重试提示，不拼接原始 message")
  void mapsUnknownFailureToSafeMessage() {
    RuntimeException cause = new RuntimeException("用户试卷内容 marker-7f3a");

    String message = StructuredOutputInvoker.failureMessage("第 3 页识别失败：", cause);

    assertThat(message)
        .isEqualTo("第 3 页识别失败：请稍后重试")
        .doesNotContain("marker-7f3a", "用户试卷内容");
  }

  @Test
  @DisplayName("安全 BusinessException 保留原始 cause 供内部分类")
  void keepsOriginalCauseForInternalClassification() {
    OpenAIIoException cause = new OpenAIIoException("sensitive prompt content");
    BusinessException error = new BusinessException(
        ErrorCode.PAPER_RECOGNITION_FAILED,
        StructuredOutputInvoker.failureMessage("第 3 页识别失败：", cause),
        cause);

    assertThat(error.getCause()).isSameAs(cause);
    assertThat(error.getMessage()).doesNotContain("sensitive prompt content");
  }

  @Test
  @DisplayName("真实调用失败时返回安全 BusinessException 并保留 cause")
  void invocationReturnsSafeBusinessException() {
    String marker = "用户试卷内容 marker-7f3a";
    OpenAIIoException cause = new OpenAIIoException(marker);
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.call()).thenThrow(cause);

    StructuredOutputProperties properties = new StructuredOutputProperties();
    properties.setStructuredMaxAttempts(1);
    StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties, null, null);
    BeanOutputConverter<Object> converter = new BeanOutputConverter<>(Object.class);

    BusinessException error = catchThrowableOfType(
        () -> invoker.invokeWithoutSchemaValidation(
            chatClient,
            "system " + marker,
            marker,
            converter,
            ErrorCode.PAPER_RECOGNITION_FAILED,
            "第 3 页识别失败：",
            "test",
            LoggerFactory.getLogger(StructuredOutputInvokerFailureTest.class)),
        BusinessException.class);

    assertThat(error.getMessage())
        .isEqualTo("第 3 页识别失败：AI 服务连接失败或响应超时，请稍后重试")
        .doesNotContain(marker);
    assertThat(error.getCause()).isSameAs(cause);
  }

  @Test
  @DisplayName("视觉单次入口不会在超时后重复发送同一请求")
  void visionOnceDoesNotRepeatTransportRequest() {
    OpenAIIoException cause = new OpenAIIoException("timeout");
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.call()).thenThrow(cause);

    StructuredOutputProperties properties = new StructuredOutputProperties();
    properties.setStructuredMaxAttempts(3);
    StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties, null, null);
    BeanOutputConverter<Object> converter = new BeanOutputConverter<>(Object.class);

    catchThrowableOfType(
        () -> invoker.invokeVisionOnceWithoutSchemaValidation(
            chatClient,
            "system",
            "user",
            java.util.List.of(),
            converter,
            ErrorCode.PAPER_RECOGNITION_FAILED,
            "第 3 页识别失败：",
            "test",
            LoggerFactory.getLogger(StructuredOutputInvokerFailureTest.class)),
        BusinessException.class);

    verify(requestSpec, times(1)).call();
  }

  @Test
  @DisplayName("视觉单次入口的准入拒绝不能被包装成业务失败")
  void visionOncePropagatesAdmissionRejection() {
    VisionRequestGuard guard = mock(VisionRequestGuard.class);
    VisionRequestRejectedException rejected =
        new VisionRequestRejectedException("AI 识图请求排队已满，请稍后重试");
    when(guard.execute(any())).thenThrow(rejected);

    StructuredOutputInvoker invoker = new StructuredOutputInvoker(
        new StructuredOutputProperties(), null, guard);

    assertThatThrownBy(() -> invoker.invokeVisionOnceWithoutSchemaValidation(
        mock(ChatClient.class),
        "system",
        "user",
        java.util.List.of(),
        new BeanOutputConverter<>(Object.class),
        ErrorCode.PAPER_RECOGNITION_FAILED,
        "第 1 页识别失败：",
        "test",
        LoggerFactory.getLogger(StructuredOutputInvokerFailureTest.class)))
        .isSameAs(rejected);
  }

  @Test
  @DisplayName("Jackson 解析失败日志包含字段路径、非法值和目标类型，且不回显提示词")
  void logsSafeJacksonDiagnosticsFromInvocation() {
    String privatePromptMarker = "用户试卷敏感提示词 marker-91f2";
    String invalidJson = """
        {
          "pageNo": 8,
          "text": "第 8 页",
          "lines": [{"text": "第 8 页", "bbox": ["无法解析", 0.1, 0.8, 0.1]}],
          "assets": [],
          "hasFigure": false,
          "stemFigureAnchors": [],
          "notes": null
        }
        """;
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callSpec);
    when(callSpec.content()).thenReturn(invalidJson);

    StructuredOutputProperties properties = new StructuredOutputProperties();
    properties.setStructuredMaxAttempts(1);
    StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties, null, null);

    catchThrowableOfType(
        () -> invoker.invokeWithoutSchemaValidation(
            chatClient,
            "system " + privatePromptMarker,
            privatePromptMarker,
            new BeanOutputConverter<>(PageRecognition.class),
            ErrorCode.PAPER_RECOGNITION_FAILED,
            "第 8 页识别失败：",
            "试卷识别-第8页",
            invokerLogger),
        BusinessException.class);

    ILoggingEvent event = logAppender.list.stream()
        .filter(item -> item.getLevel() == Level.ERROR)
        .filter(item -> item.getFormattedMessage().contains("结构化解析失败"))
        .findFirst()
        .orElseThrow();
    assertThat(event.getFormattedMessage())
        .contains(
            "type=InvalidFormatException",
            "path=interview.guide.modules.resume.model.PageRecognition[\"lines\"]"
                + "->java.util.ArrayList[0]"
                + "->interview.guide.modules.resume.model.PageRecognition$PageLine"
                + "[\"bbox\"]->double[][0]",
            "invalidValue=\"无法解析\"",
            "targetType=double")
        .doesNotContain(privatePromptMarker);
    assertThat(event.getThrowableProxy()).isNotNull();
    assertThat(event.getThrowableProxy().getMessage())
        .contains("path=", "invalidValue=\"无法解析\"", "targetType=double")
        .doesNotContain(privatePromptMarker);
  }

  @Test
  @DisplayName("语义校验失败会在原尝试次数内使用定向提示重试")
  void semanticValidationRetriesWithDirectedInstruction() {
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callSpec);
    when(callSpec.content()).thenReturn(EMPTY_BLOCK_TREE_JSON, VALID_BLOCK_TREE_JSON);

    StructuredOutputProperties properties = new StructuredOutputProperties();
    properties.setStructuredMaxAttempts(2);
    StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties, null, null);
    String retryInstruction = "不要返回 JSON Schema，必须返回 nodes 数组";

    BlockTreeDTO result = invoker.invokeWithoutSchemaValidation(
        chatClient,
        "system",
        "user",
        new BeanOutputConverter<>(BlockTreeDTO.class),
        ErrorCode.PAPER_EXTRACTION_FAILED,
        "试卷结构化失败：",
        "test",
        invokerLogger,
        blocks -> blocks != null && blocks.nodes() != null && !blocks.nodes().isEmpty(),
        retryInstruction);

    assertThat(result.nodes()).hasSize(1);
    verify(requestSpec, times(2)).call();
    org.mockito.ArgumentCaptor<String> systemCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    verify(requestSpec, times(2)).system(systemCaptor.capture());
    assertThat(systemCaptor.getAllValues().getLast())
        .contains(retryInstruction)
        .doesNotContain("上次输出解析失败");
  }

  @Test
  @DisplayName("语义校验在最后一次尝试失败时返回安全文案，不增加尝试次数")
  void semanticValidationDoesNotAddAttempts() {
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callSpec);
    when(callSpec.content()).thenReturn(EMPTY_BLOCK_TREE_JSON);

    StructuredOutputProperties properties = new StructuredOutputProperties();
    properties.setStructuredMaxAttempts(1);
    StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties, null, null);

    BusinessException error = catchThrowableOfType(
        () -> invoker.invokeWithoutSchemaValidation(
            chatClient,
            "system",
            "user",
            new BeanOutputConverter<>(BlockTreeDTO.class),
            ErrorCode.PAPER_EXTRACTION_FAILED,
            "试卷结构化失败：",
            "test",
            invokerLogger,
            blocks -> blocks != null && blocks.nodes() != null && !blocks.nodes().isEmpty(),
            "不要返回 JSON Schema"),
        BusinessException.class);

    assertThat(error.getMessage())
        .isEqualTo("试卷结构化失败：请稍后重试")
        .doesNotContain("不要返回 JSON Schema");
    verify(requestSpec, times(1)).call();
  }

  @Test
  @DisplayName("可重试失败在重试前退避，不可重试失败不等待")
  void backsOffOnlyForRetryableFailures() {
    ChatClient retryableClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec retryableRequest = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec retryableResponse = mock(ChatClient.CallResponseSpec.class);
    when(retryableClient.prompt()).thenReturn(retryableRequest);
    when(retryableRequest.system(anyString())).thenReturn(retryableRequest);
    when(retryableRequest.user(any(Consumer.class))).thenReturn(retryableRequest);
    when(retryableRequest.call())
        .thenThrow(new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED, "限流"))
        .thenReturn(retryableResponse);
    when(retryableResponse.content()).thenReturn(VALID_BLOCK_TREE_JSON);

    AtomicInteger sleeps = new AtomicInteger();
    StructuredOutputInvoker invoker = new StructuredOutputInvoker(
        new StructuredOutputProperties(), null, null) {
      @Override
      protected void sleepRetryBackoff(long millis) {
        sleeps.incrementAndGet();
      }
    };

    invoker.invokeWithoutSchemaValidation(
        retryableClient,
        "system",
        "user",
        new BeanOutputConverter<>(BlockTreeDTO.class),
        ErrorCode.PAPER_EXTRACTION_FAILED,
        "试卷结构化失败：",
        "test",
        invokerLogger);

    assertThat(sleeps).hasValue(1);

    ChatClient nonRetryableClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec nonRetryableRequest =
        mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec nonRetryableResponse = mock(ChatClient.CallResponseSpec.class);
    when(nonRetryableClient.prompt()).thenReturn(nonRetryableRequest);
    when(nonRetryableRequest.system(anyString())).thenReturn(nonRetryableRequest);
    when(nonRetryableRequest.user(any(Consumer.class))).thenReturn(nonRetryableRequest);
    when(nonRetryableRequest.call())
        .thenThrow(new IllegalArgumentException("bad json"))
        .thenReturn(nonRetryableResponse);
    when(nonRetryableResponse.content()).thenReturn(VALID_BLOCK_TREE_JSON);

    AtomicInteger nonRetryableSleeps = new AtomicInteger();
    StructuredOutputInvoker nonRetryableInvoker = new StructuredOutputInvoker(
        new StructuredOutputProperties(), null, null) {
      @Override
      protected void sleepRetryBackoff(long millis) {
        nonRetryableSleeps.incrementAndGet();
      }
    };
    nonRetryableInvoker.invokeWithoutSchemaValidation(
        nonRetryableClient,
        "system",
        "user",
        new BeanOutputConverter<>(BlockTreeDTO.class),
        ErrorCode.PAPER_EXTRACTION_FAILED,
        "试卷结构化失败：",
        "test",
        invokerLogger);

    assertThat(nonRetryableSleeps).hasValue(0);
  }

  @Test
  @DisplayName("上游 429 按 Retry-After 退避后重试成功")
  void retriesUpstreamRateLimitAfterRetryAfter() {
    RateLimitException rateLimit = RateLimitException.builder()
        .headers(Headers.builder().put("retry-after", "2").build())
        .build();
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec =
        mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.call()).thenThrow(rateLimit).thenReturn(callSpec);
    when(callSpec.content()).thenReturn(VALID_BLOCK_TREE_JSON);

    AtomicLong sleptMillis = new AtomicLong();
    StructuredOutputInvoker invoker = new StructuredOutputInvoker(
        new StructuredOutputProperties(), null, null) {
      @Override
      protected void sleepRetryBackoff(long millis) {
        sleptMillis.set(millis);
      }
    };

    BlockTreeDTO result = invoker.invokeWithoutSchemaValidation(
        chatClient,
        "system",
        "user",
        new BeanOutputConverter<>(BlockTreeDTO.class),
        ErrorCode.PAPER_EXTRACTION_FAILED,
        "试卷结构化失败：",
        "test",
        invokerLogger);

    assertThat(result.nodes()).hasSize(1);
    assertThat(sleptMillis).hasValue(2_000L);
    verify(requestSpec, times(2)).call();
  }

  @Test
  @DisplayName("持续上游 429 最终映射为限流业务错误")
  void mapsPersistentUpstreamRateLimitToRateLimitErrorCode() {
    RateLimitException rateLimit = RateLimitException.builder()
        .headers(Headers.builder().build())
        .build();
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec =
        mock(ChatClient.ChatClientRequestSpec.class);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.call()).thenThrow(rateLimit);

    StructuredOutputProperties properties = new StructuredOutputProperties();
    properties.setStructuredMaxAttempts(1);
    StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties, null, null);

    BusinessException error = catchThrowableOfType(
        () -> invoker.invokeWithoutSchemaValidation(
            chatClient,
            "system",
            "user",
            new BeanOutputConverter<>(BlockTreeDTO.class),
            ErrorCode.PAPER_EXTRACTION_FAILED,
            "试卷结构化失败：",
            "test",
            invokerLogger),
        BusinessException.class);

    assertThat(error.getCode())
        .isEqualTo(ErrorCode.AI_RATE_LIMIT_EXCEEDED.getCode());
    assertThat(error.getCause()).isSameAs(rateLimit);
  }
}
