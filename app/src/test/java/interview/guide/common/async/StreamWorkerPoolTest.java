package interview.guide.common.async;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Stream worker 池")
class StreamWorkerPoolTest {

  @Test
  @DisplayName("多个 worker 可以并发执行不同消息")
  void executesTasksConcurrently() throws Exception {
    StreamWorkerPool pool = newPool("concurrency", 2, 2, null);
    CountDownLatch bothStarted = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);

    assertThat(pool.submitBlocking(() -> awaitBoth(bothStarted, release), () -> true)).isTrue();
    assertThat(pool.submitBlocking(() -> awaitBoth(bothStarted, release), () -> true)).isTrue();

    assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    assertThat(pool.awaitIdle(Duration.ofSeconds(2))).isTrue();
    pool.shutdown();
  }

  @Test
  @DisplayName("在途容量满时阻塞投递，容量释放后继续")
  void blocksWhenInFlightCapacityIsFull() throws Exception {
    StreamWorkerPool pool = newPool("backpressure", 1, 1, null);
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    assertThat(pool.submitBlocking(() -> {
      firstStarted.countDown();
      await(release);
    }, () -> true)).isTrue();
    assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(pool.submitBlocking(() -> await(release), () -> true)).isTrue();

    ExecutorService caller = newCallerExecutor();
    try {
      Future<Boolean> third = caller.submit(
          () -> pool.submitBlocking(() -> {
          }, () -> true));
      Thread.sleep(250);
      assertThat(third.isDone()).isFalse();

      release.countDown();
      assertThat(third.get(2, TimeUnit.SECONDS)).isTrue();
      assertThat(pool.awaitIdle(Duration.ofSeconds(2))).isTrue();
    } finally {
      caller.shutdownNow();
      pool.shutdown();
    }
  }

  @Test
  @DisplayName("停止信号出现时阻塞投递不执行也不占用许可")
  void stopSignalEndsBlockedSubmission() throws Exception {
    StreamWorkerPool pool = newPool("stop", 1, 1, null);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch firstStarted = new CountDownLatch(1);
    assertThat(pool.submitBlocking(() -> {
      firstStarted.countDown();
      await(release);
    }, () -> true)).isTrue();
    assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(pool.submitBlocking(() -> await(release), () -> true)).isTrue();

    AtomicBoolean keepRunning = new AtomicBoolean(true);
    ExecutorService caller = newCallerExecutor();
    try {
      Future<Boolean> blocked = caller.submit(
          () -> pool.submitBlocking(() -> {
          }, keepRunning::get));
      Thread.sleep(250);
      assertThat(blocked.isDone()).isFalse();
      keepRunning.set(false);
      assertThat(blocked.get(2, TimeUnit.SECONDS)).isFalse();
    } finally {
      release.countDown();
      caller.shutdownNow();
      pool.shutdown();
    }
  }

  @Test
  @DisplayName("ACK 失败只累加指标，不向调用方抛出")
  void leaseSwallowsAckFailureAndRecordsMetric() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    StreamWorkerPool pool = newPool("ack-failure", 1, 1, registry);
    interview.guide.infrastructure.redis.RedisService redisService =
        mock(interview.guide.infrastructure.redis.RedisService.class);
    Mockito.doThrow(new IllegalStateException("redis down"))
        .when(redisService)
        .streamAck("stream", "group", new org.redisson.api.stream.StreamMessageId(1, 0));

    StreamMessageLease lease = new StreamMessageLease(
        redisService,
        pool,
        "stream",
        "group",
        new org.redisson.api.stream.StreamMessageId(1, 0)
    );

    lease.ack();

    assertThat(registry.counter(
        "app.stream.ack.failure",
        "consumer", "ack-failure",
        "stream", "stream"
    ).count()).isEqualTo(1.0);
    pool.shutdown();
  }

  @Test
  @DisplayName("停止 ACK 后消息保持 Pending")
  void stoppedPoolDoesNotAck() {
    StreamWorkerPool pool = newPool("stopped-ack", 1, 1, null);
    interview.guide.infrastructure.redis.RedisService redisService =
        mock(interview.guide.infrastructure.redis.RedisService.class);
    StreamMessageLease lease = new StreamMessageLease(
        redisService,
        pool,
        "stream",
        "group",
        new org.redisson.api.stream.StreamMessageId(1, 0)
    );

    pool.stopAcking();
    lease.ack();

    verifyNoInteractions(redisService);
    pool.shutdown();
  }

  private StreamWorkerPool newPool(
      String name,
      int workers,
      int queueCapacity,
      SimpleMeterRegistry registry
  ) {
    ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
        mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    BoundedExecutorFactory factory = new BoundedExecutorFactory(provider);
    BoundedExecutorProperties executorProperties = new BoundedExecutorProperties() {
    };
    executorProperties.setPoolSize(workers);
    executorProperties.setQueueCapacity(queueCapacity);
    executorProperties.setRejectionPolicy(ExecutorRejectionPolicy.ABORT);
    executorProperties.setPropagateSecurityContext(false);
    BoundedExecutor executor = factory.create("stream-" + name, executorProperties);
    return new StreamWorkerPool(
        name,
        "stream",
        "group",
        executor,
        new StreamConsumerProperties.ResolvedWorkerSettings(
            workers,
            queueCapacity,
            Duration.ofSeconds(2),
            false
        ),
        provider
    );
  }

  private static void awaitBoth(CountDownLatch started, CountDownLatch release) {
    started.countDown();
    await(release);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static ExecutorService newCallerExecutor() {
    return new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(1),
        runnable -> {
          Thread thread = new Thread(runnable, "stream-worker-pool-test-caller");
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy()
    );
  }
}
