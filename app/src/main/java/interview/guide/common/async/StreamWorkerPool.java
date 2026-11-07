package interview.guide.common.async;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 一个 Stream 专用的在途任务池。
 *
 * <p>Redis 仍是持久队列；这里的信号量只限制“已读取但尚未处理完”的消息数量，
 * 避免本地内存无界增长。
 */
@Slf4j
public final class StreamWorkerPool implements AutoCloseable {

  private final String consumerKey;
  private final String streamKey;
  private final String groupName;
  private final BoundedExecutor executor;
  private final Duration drainTimeout;
  private final Semaphore permits;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean ackAllowed = new AtomicBoolean(true);

  private final Counter dispatchedCounter;
  private final Counter completedCounter;
  private final Counter failedCounter;
  private final Counter retriedCounter;
  private final Counter ackFailureCounter;

  StreamWorkerPool(
      String consumerKey,
      String streamKey,
      String groupName,
      BoundedExecutor executor,
      StreamConsumerProperties.ResolvedWorkerSettings settings,
      ObjectProvider<MeterRegistry> meterRegistryProvider
  ) {
    this.consumerKey = consumerKey;
    this.streamKey = streamKey;
    this.groupName = groupName;
    this.executor = executor;
    this.drainTimeout = settings.drainTimeout();
    this.permits = new Semaphore(settings.workerThreads() + settings.queueCapacity());

    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    this.dispatchedCounter = counter(registry, "app.stream.dispatched",
        "成功放入 Stream worker 在途队列的消息数");
    this.completedCounter = counter(registry, "app.stream.completed",
        "业务完成并准备 ACK 的消息数");
    this.failedCounter = counter(registry, "app.stream.failed",
        "确定性失败并准备 ACK 的消息数");
    this.retriedCounter = counter(registry, "app.stream.retried",
        "重投新消息并准备 ACK 旧消息的次数");
    this.ackFailureCounter = counter(registry, "app.stream.ack.failure",
        "Stream 消息 ACK 失败次数");

    if (registry != null) {
      Gauge.builder("app.stream.worker.inflight", inFlight, AtomicInteger::get)
          .description("Stream worker 在途任务数")
          .tags("consumer", consumerKey, "stream", streamKey)
          .register(registry);
      Gauge.builder("app.stream.worker.queue", executor, BoundedExecutor::queueSize)
          .description("Stream worker 等待队列深度")
          .tags("consumer", consumerKey, "stream", streamKey)
          .register(registry);
    }
  }

  /**
   * 阻塞投递一条消息。停止信号出现、线程被中断或执行器拒绝时返回 false，调用方不得 ACK。
   */
  public boolean submitBlocking(Runnable task, BooleanSupplier keepRunning) {
    while (accepting.get() && keepRunning.getAsBoolean()) {
      if (Thread.currentThread().isInterrupted()) {
        return false;
      }
      try {
        if (!permits.tryAcquire(200, TimeUnit.MILLISECONDS)) {
          continue;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }

      inFlight.incrementAndGet();
      try {
        executor.execute(() -> runAndRelease(task));
        increment(dispatchedCounter);
        return true;
      } catch (RuntimeException e) {
        inFlight.decrementAndGet();
        permits.release();
        log.error("提交 Stream worker 任务失败，消息保持 Pending: consumer={}, stream={}",
            consumerKey, streamKey, e);
        return false;
      }
    }
    return false;
  }

  public boolean awaitIdle(Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (inFlight.get() > 0) {
      if (System.nanoTime() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
  }

  public Duration drainTimeout() {
    return drainTimeout;
  }

  public int inFlight() {
    return inFlight.get();
  }

  public int queueDepth() {
    return executor.queueSize();
  }

  public void recordCompleted() {
    increment(completedCounter);
  }

  public void recordFailed() {
    increment(failedCounter);
  }

  public void recordRetried() {
    increment(retriedCounter);
  }

  public void recordAckFailure() {
    increment(ackFailureCounter);
  }

  public boolean ackAllowed() {
    return ackAllowed.get();
  }

  public void stopAcking() {
    ackAllowed.set(false);
  }

  public void shutdown() {
    accepting.set(false);
    ackAllowed.set(false);
    executor.shutdown();
  }

  String streamKey() {
    return streamKey;
  }

  String groupName() {
    return groupName;
  }

  @Override
  public void close() {
    shutdown();
  }

  private void runAndRelease(Runnable task) {
    try {
      task.run();
    } catch (RuntimeException e) {
      log.error("Stream worker 任务异常退出: consumer={}, stream={}",
          consumerKey, streamKey, e);
    } finally {
      inFlight.decrementAndGet();
      permits.release();
    }
  }

  private Counter counter(MeterRegistry registry, String name, String description) {
    if (registry == null) {
      return null;
    }
    return Counter.builder(name)
        .description(description)
        .tags("consumer", consumerKey, "stream", streamKey)
        .register(registry);
  }

  private static void increment(Counter counter) {
    if (counter != null) {
      counter.increment();
    }
  }
}
