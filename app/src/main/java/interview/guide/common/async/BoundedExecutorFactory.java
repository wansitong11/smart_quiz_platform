package interview.guide.common.async;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 构建所有功能共用的命名有界线程池：线程命名、有界队列、拒绝策略、Micrometer 指标与
 * SecurityContext/MDC 传播只在这里实现一次。
 */
@Component
public class BoundedExecutorFactory {

  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final Set<String> createdNames = ConcurrentHashMap.newKeySet();

  public BoundedExecutorFactory(ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.meterRegistryProvider = meterRegistryProvider;
  }

  public BoundedExecutor create(String name, BoundedExecutorProperties properties) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("executor name must not be blank");
    }
    if (properties.getPoolSize() < 1 || properties.getQueueCapacity() < 1) {
      throw new IllegalArgumentException(
          "executor " + name + " poolSize and queueCapacity must be >= 1");
    }
    if (!createdNames.add(name)) {
      throw new IllegalStateException("executor already created: " + name);
    }

    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    Counter rejectedCounter = registry == null
        ? null
        : Counter.builder("app.executor.rejected")
            .description("有界执行器因队列满被拒绝的任务数")
            .tag("executor", name)
            .register(registry);

    ThreadPoolExecutor pool = new ThreadPoolExecutor(
        properties.getPoolSize(),
        properties.getPoolSize(),
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(properties.getQueueCapacity()),
        threadFactory(name),
        rejectionHandler(properties.getRejectionPolicy(), rejectedCounter));

    if (registry != null) {
      registerGauges(registry, name, pool);
    }
    return new BoundedExecutor(name, pool,
        properties.isPropagateSecurityContext(), properties.isPropagateAiCallContext());
  }

  private static void registerGauges(MeterRegistry registry, String name, ThreadPoolExecutor pool) {
    Gauge.builder("app.executor.active", pool, p -> p.getActiveCount())
        .description("正在执行任务的线程数")
        .tag("executor", name)
        .register(registry);
    Gauge.builder("app.executor.queue.size", pool, p -> p.getQueue().size())
        .description("排队中的任务数")
        .tag("executor", name)
        .register(registry);
    Gauge.builder("app.executor.queue.remaining", pool, p -> p.getQueue().remainingCapacity())
        .description("队列剩余容量")
        .tag("executor", name)
        .register(registry);
  }

  private static RejectedExecutionHandler rejectionHandler(
      ExecutorRejectionPolicy policy, Counter rejectedCounter) {
    RejectedExecutionHandler delegate = switch (policy) {
      case ABORT -> new ThreadPoolExecutor.AbortPolicy();
      case CALLER_RUNS -> new ThreadPoolExecutor.CallerRunsPolicy();
    };
    return (task, executor) -> {
      if (rejectedCounter != null) {
        rejectedCounter.increment();
      }
      delegate.rejectedExecution(task, executor);
    };
  }

  private static ThreadFactory threadFactory(String name) {
    AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, name + "-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
