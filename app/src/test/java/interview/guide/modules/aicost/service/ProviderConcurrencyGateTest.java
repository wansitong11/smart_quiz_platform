package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Provider 级并发闸门")
class ProviderConcurrencyGateTest {

  private ProviderConcurrencyProperties properties;
  private ProviderConcurrencyGate gate;

  @BeforeEach
  void setUp() {
    properties = new ProviderConcurrencyProperties();
    gate = new ProviderConcurrencyGate(properties);
  }

  @Test
  @DisplayName("付费识图 Provider 配置为 1 时后续调用等待")
  void limitsPaidVisionProvider() throws Exception {
    ProviderConcurrencyProperties.ProviderLimits limits =
        new ProviderConcurrencyProperties.ProviderLimits();
    limits.setVisionPermits(1);
    properties.getProviders().put("paid-vision", limits);

    ProviderConcurrencyGate.Permit first = gate.acquire("paid-vision", "VISION");
    AtomicBoolean secondAcquired = new AtomicBoolean(false);
    CountDownLatch secondDone = new CountDownLatch(1);
    Thread worker = Thread.ofVirtual().start(() -> {
      try (ProviderConcurrencyGate.Permit ignored =
               gate.acquire("paid-vision", "VISION")) {
        secondAcquired.set(true);
      } finally {
        secondDone.countDown();
      }
    });

    try {
      assertThat(secondDone.await(200, TimeUnit.MILLISECONDS)).isFalse();
      first.close();
      assertThat(secondDone.await(2, TimeUnit.SECONDS)).isTrue();
      worker.join(2_000);
      assertThat(secondAcquired).isTrue();
    } finally {
      first.close();
    }
  }

  @Test
  @DisplayName("未配置的 Provider 不改变原有并发")
  void leavesUnconfiguredProviderUnlimited() {
    assertThat(gate.isConfigured("unconfigured", "VISION")).isFalse();

    try (ProviderConcurrencyGate.Permit first = gate.acquire("unconfigured", "VISION");
         ProviderConcurrencyGate.Permit second = gate.acquire("unconfigured", "VISION")) {
      assertThat(first).isNotNull();
      assertThat(second).isNotNull();
    }
  }
}
