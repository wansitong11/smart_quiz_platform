package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.config.LlmProviderProperties;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("免费模型统一并发闸门")
class FreeModelConcurrencyGateTest {

  private FreeModelConcurrencyProperties properties;
  private AiCostCalculator costCalculator;
  private LlmProviderProperties providerProperties;
  private FreeModelConcurrencyGate gate;

  @BeforeEach
  void setUp() {
    properties = new FreeModelConcurrencyProperties();
    costCalculator = mock(AiCostCalculator.class);
    providerProperties = new LlmProviderProperties();
    gate = new FreeModelConcurrencyGate(properties, costCalculator, providerProperties);
    gate.initPermits();
  }

  @Test
  @DisplayName("文本、识图、Embedding、音频的零价格模型都判定为免费")
  void detectsAllPricedFreeModalities() {
    when(costCalculator.freeStatus(anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(true));

    assertThat(gate.isFree("glm-free", "glm-4.7-flash", "TEXT")).isTrue();
    assertThat(gate.isFree("glm-vl-free", "glm-4.6v-flash", "VISION")).isTrue();
    assertThat(gate.isFree("siliconflow-free", "BAAI/bge-m3", "EMBEDDING")).isTrue();
    assertThat(gate.isFree("dashscope", "qwen3-tts-flash-realtime", "AUDIO")).isTrue();
  }

  @Test
  @DisplayName("有价格记录的付费模型不受免费并发限制")
  void paidPriceOverridesConfiguredFreeFallback() {
    LlmProviderProperties.FreeModels freeModels = providerProperties.getFreeModels();
    freeModels.setTextProvider("siliconflow-cheap");
    when(costCalculator.freeStatus(
        "siliconflow-cheap", "Qwen/Qwen3-14B", "TEXT"))
        .thenReturn(Optional.of(false));

    assertThat(gate.isFree("siliconflow-cheap", "Qwen/Qwen3-14B", "TEXT")).isFalse();
  }

  @Test
  @DisplayName("价格表缺失时回退到免费 Provider 配置")
  void fallsBackToConfiguredFreeProvidersWithoutPrice() {
    LlmProviderProperties.FreeModels freeModels = providerProperties.getFreeModels();
    freeModels.setTextProvider("free-text");
    freeModels.setVisionProvider("free-vision");
    freeModels.setEmbeddingProvider("free-embedding");
    freeModels.setAudioProvider("free-audio");
    when(costCalculator.freeStatus(anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());

    assertThat(gate.isFree("free-text", "text-model", "TEXT")).isTrue();
    assertThat(gate.isFree("free-vision", "vision-model", "VISION")).isTrue();
    assertThat(gate.isFree("free-embedding", "embedding-model", "EMBEDDING")).isTrue();
    assertThat(gate.isFree("free-audio", "audio-model", "AUDIO")).isTrue();
  }

  @Test
  @DisplayName("免费文本许可耗尽时后续调用等待，释放后继续")
  void waitsForFreeTextPermit() throws Exception {
    when(costCalculator.freeStatus(anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(true));

    FreeModelConcurrencyGate.Permit first =
        gate.acquireIfFree("free-text", "text-model", "TEXT");
    AtomicBoolean secondAcquired = new AtomicBoolean(false);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch secondDone = new CountDownLatch(1);

    Thread worker = Thread.ofVirtual().start(() -> {
      try (FreeModelConcurrencyGate.Permit ignored =
               gate.acquireIfFree("free-text", "text-model", "TEXT")) {
        secondAcquired.set(true);
      } catch (Throwable error) {
        failure.set(error);
      } finally {
        secondDone.countDown();
      }
    });

    assertThat(secondDone.await(200, TimeUnit.MILLISECONDS)).isFalse();
    first.close();
    assertThat(secondDone.await(2, TimeUnit.SECONDS)).isTrue();
    worker.join(2_000);

    assertThat(failure.get()).isNull();
    assertThat(secondAcquired).isTrue();
  }

  @Test
  @DisplayName("Provider 级许可覆盖默认值，免费文本可并发 8 个")
  void usesProviderSpecificPermits() throws Exception {
    FreeModelConcurrencyProperties.ProviderPermits permits =
        new FreeModelConcurrencyProperties.ProviderPermits();
    permits.setTextPermits(8);
    properties.getProviderPermits().put("free-fast", permits);
    when(costCalculator.freeStatus(anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(true));

    FreeModelConcurrencyGate.Permit[] held = new FreeModelConcurrencyGate.Permit[8];
    for (int i = 0; i < held.length; i++) {
      held[i] = gate.acquireIfFree("free-fast", "fast-model", "TEXT");
    }

    AtomicBoolean ninthAcquired = new AtomicBoolean(false);
    CountDownLatch ninthDone = new CountDownLatch(1);
    Thread worker = Thread.ofVirtual().start(() -> {
      try (FreeModelConcurrencyGate.Permit ignored =
               gate.acquireIfFree("free-fast", "fast-model", "TEXT")) {
        ninthAcquired.set(true);
      } finally {
        ninthDone.countDown();
      }
    });

    try {
      assertThat(ninthDone.await(200, TimeUnit.MILLISECONDS)).isFalse();
      held[0].close();
      assertThat(ninthDone.await(2, TimeUnit.SECONDS)).isTrue();
      worker.join(2_000);
      assertThat(ninthAcquired).isTrue();
    } finally {
      for (int i = 1; i < held.length; i++) {
        held[i].close();
      }
    }
  }
}
