package interview.guide.common.async;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Stream 消费并发配置")
class StreamConsumerPropertiesTest {

  @Test
  @DisplayName("消费者未配置字段由默认值补齐")
  void mergesMissingFieldsWithDefaults() {
    StreamConsumerProperties properties = new StreamConsumerProperties();
    StreamConsumerProperties.WorkerSettings defaults = new StreamConsumerProperties.WorkerSettings();
    defaults.setWorkerThreads(3);
    defaults.setQueueCapacity(12);
    defaults.setDrainTimeout(Duration.ofSeconds(20));
    defaults.setPropagateSecurityContext(false);
    properties.setDefaults(defaults);

    StreamConsumerProperties.WorkerSettings consumer = new StreamConsumerProperties.WorkerSettings();
    consumer.setWorkerThreads(5);
    LinkedHashMap<String, StreamConsumerProperties.WorkerSettings> consumers =
        new LinkedHashMap<>();
    consumers.put("paper-input", consumer);
    properties.setConsumers(consumers);

    StreamConsumerProperties.ResolvedWorkerSettings resolved =
        properties.resolve("paper-input");

    assertThat(resolved.workerThreads()).isEqualTo(5);
    assertThat(resolved.queueCapacity()).isEqualTo(12);
    assertThat(resolved.drainTimeout()).isEqualTo(Duration.ofSeconds(20));
    assertThat(resolved.propagateSecurityContext()).isFalse();
  }

  @Test
  @DisplayName("未知消费者完全使用默认值")
  void unknownConsumerUsesDefaults() {
    StreamConsumerProperties properties = new StreamConsumerProperties();

    StreamConsumerProperties.ResolvedWorkerSettings resolved =
        properties.resolve("unknown-consumer");

    assertThat(resolved.workerThreads()).isEqualTo(2);
    assertThat(resolved.queueCapacity()).isEqualTo(20);
    assertThat(resolved.drainTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(resolved.propagateSecurityContext()).isTrue();
  }

  @Test
  @DisplayName("非法并发参数被拒绝")
  void rejectsInvalidWorkerCount() {
    StreamConsumerProperties properties = new StreamConsumerProperties();
    StreamConsumerProperties.WorkerSettings consumer = new StreamConsumerProperties.WorkerSettings();
    consumer.setWorkerThreads(0);
    properties.setConsumers(new LinkedHashMap<>(
        java.util.Map.of("paper-input", consumer)
    ));

    assertThatThrownBy(() -> properties.resolve("paper-input"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workerThreads");
  }
}
