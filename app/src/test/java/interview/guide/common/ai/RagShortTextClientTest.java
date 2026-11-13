package interview.guide.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.junit.jupiter.api.AfterEach;

class RagShortTextClientTest {
  public record Decision(List<String> keepIds) { }

  @AfterEach
  void clearContext() { AiCallContextHolder.clear(); }

  @Test
  @DisplayName("短客户端仍遵守免费固定模型与授权用户系统默认模型的优先级")
  void preservesTierRouting() {
    var properties = new LlmProviderProperties();
    var config = new LlmProviderProperties.ProviderConfig();
    config.setBaseUrl("http://127.0.0.1:12345/v1");
    config.setApiKey("test-key");
    config.setModel("test-model");
    properties.setDefaultProvider("authorized");
    properties.getTier().setFreeTextProvider("free");
    properties.setProviders(Map.of("free", config, "authorized", config, "requested", config));
    var registry = new LlmProviderRegistry(properties, null, null, null);
    var tier = mock(AiUserTierResolver.class);
    registry.setAiUserTierResolver(tier);
    AiCallContextHolder.set(new AiCallContextHolder.AiCallContext(7L, AiFeature.KB_QA,
        "test", "request", "operation", "test"));
    var limits = new LlmProviderRegistry.ShortTextOptions(Duration.ofSeconds(2), 256);
    when(tier.isFreeTier(7L)).thenReturn(true);
    var free = registry.getChatClientOrDefault("requested", limits);
    assertThat(free).isSameAs(registry.getChatClientOrDefault("free", limits));
    when(tier.isFreeTier(7L)).thenReturn(false);
    when(tier.isAuthorizedTier(7L)).thenReturn(true);
    var authorized = registry.getChatClientOrDefault("requested", limits);
    assertThat(authorized).isNotSameAs(free)
        .isSameAs(registry.getChatClientOrDefault("authorized", limits));
  }

  @Test
  @DisplayName("短文本客户端独立缓存，实际请求限制输出并关闭思考，不携带工具")
  void shortClientWireContract() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = """
          {"id":"test","object":"chat.completion","model":"qwen-test","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      var properties = new LlmProviderProperties();
      var config = new LlmProviderProperties.ProviderConfig();
      config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
      config.setApiKey("test-key");
      config.setModel("qwen-test");
      properties.setProviders(Map.of("dashscope-test", config));
      properties.setDefaultProvider("dashscope-test");
      var registry = new LlmProviderRegistry(properties, null, null, null);
      var limits = new LlmProviderRegistry.ShortTextOptions(Duration.ofSeconds(2), 256);
      var client = registry.getChatClientOrDefault(null, limits);
      assertThat(client).isSameAs(registry.getChatClientOrDefault("dashscope-test", limits));
      assertThat(client).isNotSameAs(registry.getPlainChatClient());
      assertThat(client.prompt().system("只复核").user("问题").call().content()).isEqualTo("ok");
      assertThat(body.get()).contains("\"max_tokens\":256", "\"enable_thinking\":false")
          .doesNotContain("\"tools\"", "\"stream\":true");
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("文本单次入口只调用一次，不进入视觉准入，也不做语义修复重试")
  void textOnceDoesNotUseVisionOrRetry() {
    var client = mock(ChatClient.class);
    var request = mock(ChatClient.ChatClientRequestSpec.class);
    when(client.prompt()).thenReturn(request);
    when(request.system(anyString())).thenReturn(request);
    when(request.user(any(Consumer.class))).thenReturn(request);
    when(request.call()).thenThrow(new IllegalStateException("timeout"));
    var guard = mock(VisionRequestGuard.class);
    var properties = new StructuredOutputProperties();
    properties.setStructuredMaxAttempts(3);
    var invoker = new StructuredOutputInvoker(properties, null, guard);
    assertThatThrownBy(() -> invoker.invokeOnceWithoutSchemaValidation(client, "system", "question",
        new BeanOutputConverter<>(Decision.class), ErrorCode.RAG_EVIDENCE_REVIEW_FAILED,
        "资料复核失败", "rag-review", LoggerFactory.getLogger(getClass()), decision -> true))
        .isInstanceOf(BusinessException.class);
    verify(request, times(1)).call();
    verifyNoInteractions(guard);
  }
}
