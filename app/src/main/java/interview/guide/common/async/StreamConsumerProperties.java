package interview.guide.common.async;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis Stream 消费并发配置。
 *
 * <p>每个消费者可以单独设置分发线程之后的 worker 数量、等待队列容量和停机等待时间。
 * 未显式配置的字段由 {@link #defaults} 补齐。
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.async.stream")
public class StreamConsumerProperties {

  @Min(1000)
  private long pendingMetricsIntervalMs = 30000;

  @Valid
  @NotNull
  private WorkerSettings defaults = WorkerSettings.withDefaults();

  @Valid
  private Map<String, WorkerSettings> consumers = new LinkedHashMap<>();

  /**
   * 解析某个消费者的有效配置；返回不可变副本，不修改原始配置对象。
   */
  public ResolvedWorkerSettings resolve(String consumerKey) {
    if (consumerKey == null || consumerKey.isBlank()) {
      throw new IllegalArgumentException("consumerKey must not be blank");
    }

    WorkerSettings configured = consumers == null ? null : consumers.get(consumerKey);
    int workers = value(configured == null ? null : configured.getWorkerThreads(),
        defaults.getWorkerThreads());
    int queueCapacity = value(configured == null ? null : configured.getQueueCapacity(),
        defaults.getQueueCapacity());
    Duration drainTimeout = value(configured == null ? null : configured.getDrainTimeout(),
        defaults.getDrainTimeout());
    boolean propagateSecurityContext = value(
        configured == null ? null : configured.getPropagateSecurityContext(),
        defaults.getPropagateSecurityContext());

    if (workers < 1 || workers > 32) {
      throw new IllegalArgumentException(
          "consumer " + consumerKey + " workerThreads must be between 1 and 32");
    }
    if (queueCapacity < 1) {
      throw new IllegalArgumentException(
          "consumer " + consumerKey + " queueCapacity must be >= 1");
    }
    if (drainTimeout == null || drainTimeout.isZero() || drainTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "consumer " + consumerKey + " drainTimeout must be positive");
    }

    return new ResolvedWorkerSettings(
        workers,
        queueCapacity,
        drainTimeout,
        propagateSecurityContext
    );
  }

  private static int value(Integer configured, Integer fallback) {
    return configured != null ? configured : fallback;
  }

  private static Duration value(Duration configured, Duration fallback) {
    return configured != null ? configured : fallback;
  }

  private static boolean value(Boolean configured, Boolean fallback) {
    return configured != null ? configured : fallback;
  }

  @Getter
  @Setter
  public static class WorkerSettings {

    @Min(1)
    @Max(32)
    private Integer workerThreads;

    @Min(1)
    private Integer queueCapacity;

    private Duration drainTimeout;

    private Boolean propagateSecurityContext;

    static WorkerSettings withDefaults() {
      WorkerSettings settings = new WorkerSettings();
      settings.setWorkerThreads(2);
      settings.setQueueCapacity(20);
      settings.setDrainTimeout(Duration.ofSeconds(10));
      settings.setPropagateSecurityContext(true);
      return settings;
    }
  }

  public record ResolvedWorkerSettings(
      int workerThreads,
      int queueCapacity,
      Duration drainTimeout,
      boolean propagateSecurityContext
  ) {
  }
}
