package interview.guide.common.async;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按 consumerKey 创建并复用 Stream worker 池。
 */
@Slf4j
@Component
public final class StreamWorkerRegistry implements AutoCloseable {

  private final BoundedExecutorFactory executorFactory;
  private final StreamConsumerProperties properties;
  private final RedisService redisService;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final Map<String, PoolEntry> pools = new ConcurrentHashMap<>();
  private final Object creationMonitor = new Object();

  public StreamWorkerRegistry(
      BoundedExecutorFactory executorFactory,
      StreamConsumerProperties properties,
      RedisService redisService,
      ObjectProvider<MeterRegistry> meterRegistryProvider
  ) {
    this.executorFactory = executorFactory;
    this.properties = properties;
    this.redisService = redisService;
    this.meterRegistryProvider = meterRegistryProvider;
  }

  public StreamWorkerPool acquire(String consumerKey, String streamKey, String groupName) {
    validateKey(consumerKey);
    validateKey(streamKey);
    validateKey(groupName);

    PoolEntry existing = pools.get(consumerKey);
    if (existing != null) {
      existing.verify(streamKey, groupName);
      return existing.pool();
    }

    synchronized (creationMonitor) {
      existing = pools.get(consumerKey);
      if (existing != null) {
        existing.verify(streamKey, groupName);
        return existing.pool();
      }

      StreamConsumerProperties.ResolvedWorkerSettings settings = properties.resolve(consumerKey);
      BoundedExecutor executor = executorFactory.create(
          "stream-" + consumerKey,
          executorProperties(settings)
      );
      StreamWorkerPool pool = new StreamWorkerPool(
          consumerKey,
          streamKey,
          groupName,
          executor,
          settings,
          meterRegistryProvider
      );
      AtomicLong pending = registerPendingGauge(consumerKey, streamKey);
      pools.put(consumerKey, new PoolEntry(streamKey, groupName, pool, pending));
      log.info("Stream worker pool created: consumer={}, stream={}, workers={}, queueCapacity={}",
          consumerKey, streamKey, settings.workerThreads(), settings.queueCapacity());
      return pool;
    }
  }

  @Scheduled(fixedDelayString = "${app.async.stream.pending-metrics-interval-ms:30000}")
  public void samplePendingCounts() {
    for (Map.Entry<String, PoolEntry> entry : pools.entrySet()) {
      PoolEntry poolEntry = entry.getValue();
      try {
        poolEntry.pending().set(
            redisService.streamPendingCount(poolEntry.streamKey(), poolEntry.groupName()));
      } catch (Exception e) {
        log.warn("采样 Redis Stream Pending 数失败，保留上次值: consumer={}, stream={}",
            entry.getKey(), poolEntry.streamKey(), e);
      }
    }
  }

  @PreDestroy
  @Override
  public void close() {
    for (Map.Entry<String, PoolEntry> entry : pools.entrySet()) {
      StreamWorkerPool pool = entry.getValue().pool();
      try {
        if (!pool.awaitIdle(pool.drainTimeout())) {
          pool.stopAcking();
          log.warn("Stream worker 停机等待超时，未完成任务保持 Pending: consumer={}, stream={}, "
                  + "inFlight={}",
              entry.getKey(), entry.getValue().streamKey(), pool.inFlight());
        }
      } catch (Exception e) {
        log.warn("Stream worker 停机等待失败: consumer={}, stream={}",
            entry.getKey(), entry.getValue().streamKey(), e);
      }
    }
    for (Map.Entry<String, PoolEntry> entry : pools.entrySet()) {
      try {
        entry.getValue().pool().shutdown();
      } catch (Exception e) {
        log.warn("关闭 Stream worker 池失败: consumer={}, stream={}",
            entry.getKey(), entry.getValue().streamKey(), e);
      }
    }
    pools.clear();
  }

  private AtomicLong registerPendingGauge(String consumerKey, String streamKey) {
    AtomicLong pending = new AtomicLong();
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry != null) {
      Gauge.builder("app.stream.pending", pending, AtomicLong::get)
          .description("Redis Stream Pending List 中的消息数")
          .tags("consumer", consumerKey, "stream", streamKey)
          .register(registry);
    }
    return pending;
  }

  private static BoundedExecutorProperties executorProperties(
      StreamConsumerProperties.ResolvedWorkerSettings settings
  ) {
    ResolvedExecutorProperties executorProperties = new ResolvedExecutorProperties();
    executorProperties.setPoolSize(settings.workerThreads());
    executorProperties.setQueueCapacity(settings.queueCapacity());
    executorProperties.setRejectionPolicy(ExecutorRejectionPolicy.ABORT);
    executorProperties.setPropagateSecurityContext(settings.propagateSecurityContext());
    return executorProperties;
  }

  private static void validateKey(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Stream worker registry key must not be blank");
    }
  }

  private record PoolEntry(
      String streamKey,
      String groupName,
      StreamWorkerPool pool,
      AtomicLong pending
  ) {

    void verify(String expectedStreamKey, String expectedGroupName) {
      if (!streamKey.equals(expectedStreamKey) || !groupName.equals(expectedGroupName)) {
        throw new IllegalStateException(
            "consumerKey already bound to another stream/group: existing="
                + streamKey + "/" + groupName + ", requested="
                + expectedStreamKey + "/" + expectedGroupName);
      }
    }
  }

  private static final class ResolvedExecutorProperties extends BoundedExecutorProperties {
  }
}
