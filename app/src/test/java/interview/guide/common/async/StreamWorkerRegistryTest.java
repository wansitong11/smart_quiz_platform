package interview.guide.common.async;

import io.micrometer.core.instrument.MeterRegistry;
import interview.guide.infrastructure.redis.RedisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Stream worker 注册表")
class StreamWorkerRegistryTest {

  @Test
  @DisplayName("相同 consumerKey 返回同一个 worker 池")
  void reusesPoolByConsumerKey() {
    StreamWorkerRegistry registry = newRegistry();

    StreamWorkerPool first = registry.acquire("paper-input", "papers:input", "workers");
    StreamWorkerPool second = registry.acquire("paper-input", "papers:input", "workers");

    assertThat(second).isSameAs(first);
    registry.close();
  }

  @Test
  @DisplayName("同一 consumerKey 不允许绑定到另一个 Stream")
  void rejectsStreamRebinding() {
    StreamWorkerRegistry registry = newRegistry();
    registry.acquire("paper-input", "papers:input", "workers");

    assertThatThrownBy(() ->
        registry.acquire("paper-input", "papers:recognition", "workers"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already bound");
    registry.close();
  }

  @Test
  @DisplayName("不同 consumerKey 使用独立配置")
  void createsIndependentPools() {
    StreamWorkerRegistry registry = newRegistry();

    StreamWorkerPool input = registry.acquire("paper-input", "papers:input", "workers");
    StreamWorkerPool recognition = registry.acquire(
        "paper-recognition", "papers:recognition", "workers");

    assertThat(recognition).isNotSameAs(input);
    assertThat(input.queueDepth()).isZero();
    assertThat(recognition.queueDepth()).isZero();
    registry.close();
  }

  private StreamWorkerRegistry newRegistry() {
    ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
    when(meterProvider.getIfAvailable()).thenReturn(null);
    BoundedExecutorFactory factory = new BoundedExecutorFactory(meterProvider);

    StreamConsumerProperties properties = new StreamConsumerProperties();
    StreamConsumerProperties.WorkerSettings recognition =
        new StreamConsumerProperties.WorkerSettings();
    recognition.setWorkerThreads(1);
    recognition.setQueueCapacity(2);
    LinkedHashMap<String, StreamConsumerProperties.WorkerSettings> consumers =
        new LinkedHashMap<>();
    consumers.put("paper-recognition", recognition);
    properties.setConsumers(consumers);

    return new StreamWorkerRegistry(
        factory,
        properties,
        mock(RedisService.class),
        meterProvider
    );
  }
}
