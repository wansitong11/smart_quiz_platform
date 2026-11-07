package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiRateLimitThrottle;
import interview.guide.modules.aicost.AiCostProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

@DisplayName("模型调用计量器接入免费并发闸门")
class AiCallRecorderFreeModelGateTest {

  private FreeModelConcurrencyGate gate;
  private ProviderConcurrencyGate providerGate;
  private AiRateLimitThrottle throttle;
  private AiCallRecorder recorder;

  @BeforeEach
  void setUp() {
    AiCostProperties properties = new AiCostProperties();
    properties.setEnabled(false);
    gate = mock(FreeModelConcurrencyGate.class);
    providerGate = mock(ProviderConcurrencyGate.class);
    throttle = mock(AiRateLimitThrottle.class);
    when(providerGate.acquire(anyString(), anyString()))
        .thenReturn(ProviderConcurrencyGate.Permit.noop());
    recorder = new AiCallRecorder(
        properties,
        mock(AiUsageGate.class),
        mock(AiCostCalculator.class),
        mock(AiLedgerWriter.class),
        mock(AiBudgetService.class),
        gate,
        providerGate,
        throttle);
  }

  @Test
  @DisplayName("同步 Chat 调用经过免费闸门")
  void chatCallUsesFreeGate() {
    ChatClientRequest request = mock(ChatClientRequest.class);
    ChatClientResponse response = mock(ChatClientResponse.class);
    CallAdvisorChain chain = mock(CallAdvisorChain.class);
    when(chain.nextCall(request)).thenReturn(response);
    when(gate.isFree("free-text", "text-model", "TEXT")).thenReturn(true);
    when(gate.acquireIfFree("free-text", "text-model", "TEXT"))
        .thenReturn(FreeModelConcurrencyGate.Permit.noop());

    ChatClientResponse result =
        ((CallAdvisor) recorder.chatAdvisor("free-text", "text-model", "TEXT"))
            .adviseCall(request, chain);

    assertThat(result).isSameAs(response);
    verify(gate).isFree("free-text", "text-model", "TEXT");
    verify(gate).acquireIfFree("free-text", "text-model", "TEXT");
    verify(throttle).awaitOpen("free-text", "text-model", "TEXT");
  }

  @Test
  @DisplayName("免费模型调用失败后写入共享冷却")
  void chatCallNotesRateLimitFailure() {
    ChatClientRequest request = mock(ChatClientRequest.class);
    CallAdvisorChain chain = mock(CallAdvisorChain.class);
    RuntimeException failure = new RuntimeException("429");
    when(chain.nextCall(request)).thenThrow(failure);
    when(gate.isFree("free-text", "text-model", "TEXT")).thenReturn(true);
    when(gate.acquireIfFree("free-text", "text-model", "TEXT"))
        .thenReturn(FreeModelConcurrencyGate.Permit.noop());

    assertThatThrownBy(() ->
        ((CallAdvisor) recorder.chatAdvisor("free-text", "text-model", "TEXT"))
            .adviseCall(request, chain))
        .isSameAs(failure);

    verify(throttle).noteFailure("free-text", "text-model", "TEXT", failure);
  }

  @Test
  @DisplayName("配置了 Provider 许可的付费模型也进入共享冷却")
  void providerLimitedPaidCallUsesThrottle() {
    ChatClientRequest request = mock(ChatClientRequest.class);
    ChatClientResponse response = mock(ChatClientResponse.class);
    CallAdvisorChain chain = mock(CallAdvisorChain.class);
    when(chain.nextCall(request)).thenReturn(response);
    when(gate.isFree("paid-vision", "vision-model", "VISION")).thenReturn(false);
    when(providerGate.isConfigured("paid-vision", "VISION")).thenReturn(true);

    ChatClientResponse result =
        ((CallAdvisor) recorder.chatAdvisor("paid-vision", "vision-model", "VISION"))
            .adviseCall(request, chain);

    assertThat(result).isSameAs(response);
    verify(providerGate).acquire("paid-vision", "VISION");
    verify(throttle).awaitOpen("paid-vision", "vision-model", "VISION");
  }

  @Test
  @DisplayName("Embedding 的 Document 入口也必须经过免费闸门")
  void embeddingDocumentCallUsesFreeGate() {
    EmbeddingModel delegate = mock(EmbeddingModel.class);
    Document document = mock(Document.class);
    when(delegate.embed(document)).thenReturn(new float[] {0.1f, 0.2f});
    when(gate.acquireIfFree("free-embedding", "bge-m3", "EMBEDDING"))
        .thenReturn(FreeModelConcurrencyGate.Permit.noop());

    float[] result = recorder.wrapEmbedding(delegate, "free-embedding", "bge-m3")
        .embed(document);

    assertThat(result).containsExactly(0.1f, 0.2f);
    verify(gate).acquireIfFree("free-embedding", "bge-m3", "EMBEDDING");
  }
}
