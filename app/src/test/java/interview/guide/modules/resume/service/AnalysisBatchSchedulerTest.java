package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.TestBoundedExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("解析批次调度")
class AnalysisBatchSchedulerTest {

  private final List<BoundedExecutor> executors = new ArrayList<>();

  @AfterEach
  void tearDown() {
    executors.forEach(BoundedExecutor::shutdown);
  }

  @Test
  @DisplayName("正常批并发执行并按原始批次顺序返回结果")
  void shouldRunNormalBatchesAndPreserveOrder() {
    AnalysisBatchScheduler scheduler = scheduler(executor(2, 10));
    AtomicInteger heartbeats = new AtomicInteger();

    List<String> results = scheduler.runBatches(
        List.of(1, 2, 3, 4),
        batch -> () -> {
          if (batch == 2) {
            throw new IllegalStateException("batch failed");
          }
          return "ok-" + batch;
        },
        (batch, error) -> "failed-" + batch + "-" + error.getClass().getSimpleName(),
        heartbeats::incrementAndGet);

    assertThat(results).containsExactly(
        "ok-1",
        "failed-2-IllegalStateException",
        "ok-3",
        "ok-4");
    assertThat(heartbeats.get()).isEqualTo(4);
  }

  @Test
  @DisplayName("队列满时先收在途结果，再重投剩余批次")
  void shouldWaitForInFlightBatchesAndResubmitRejectedOnes() throws Exception {
    AnalysisBatchScheduler scheduler = scheduler(executor(1, 1));
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    Thread releaser = new Thread(() -> {
      try {
        firstStarted.await();
        Thread.sleep(20);
        releaseFirst.countDown();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    releaser.start();

    List<String> results = scheduler.runBatches(
        List.of(1, 2, 3),
        batch -> () -> {
          if (batch == 1) {
            firstStarted.countDown();
            releaseFirst.await();
          }
          return "ok-" + batch;
        },
        (batch, error) -> "failed-" + batch,
        null);

    releaser.join();
    assertThat(results).containsExactly("ok-1", "ok-2", "ok-3");
  }

  @Test
  @DisplayName("补跑按批次顺序执行，不会同时在途")
  void shouldRunRecoverySequentially() {
    AnalysisBatchScheduler scheduler = scheduler(executor(2, 10));
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();
    List<String> events = new CopyOnWriteArrayList<>();

    List<String> results = scheduler.runRecovery(
        List.of("A", "B"),
        batch -> () -> {
          int current = active.incrementAndGet();
          maxActive.accumulateAndGet(current, Math::max);
          events.add("start-" + batch);
          Thread.sleep(10);
          events.add("end-" + batch);
          active.decrementAndGet();
          return "done-" + batch;
        },
        (batch, error) -> "failed-" + batch,
        null,
        System.currentTimeMillis() + 5_000);

    assertThat(results).containsExactly("done-A", "done-B");
    assertThat(events).containsExactly("start-A", "end-A", "start-B", "end-B");
    assertThat(maxActive.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("补跑截止时间已过时不再提交任务，并返回失败结果")
  void shouldNotSubmitRecoveryBatchesAfterDeadline() {
    AnalysisBatchScheduler scheduler = scheduler(executor(2, 10));
    AtomicInteger submitted = new AtomicInteger();
    AtomicInteger heartbeats = new AtomicInteger();

    List<String> results = scheduler.runRecovery(
        List.of("A", "B"),
        batch -> () -> {
          submitted.incrementAndGet();
          return "done-" + batch;
        },
        (batch, error) -> "timeout-" + batch,
        heartbeats::incrementAndGet,
        System.currentTimeMillis() - 1);

    assertThat(results).containsExactly("timeout-A", "timeout-B");
    assertThat(submitted.get()).isZero();
    assertThat(heartbeats.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("执行器关闭后未提交的任务统一转失败，不阻塞调用线程")
  void shouldFailRemainingBatchesWhenExecutorIsShutdown() {
    BoundedExecutor executor = executor(1, 1);
    AnalysisBatchScheduler scheduler = scheduler(executor);
    executor.shutdown();

    List<String> results = scheduler.runBatches(
        List.of(1, 2),
        batch -> () -> "ok-" + batch,
        (batch, error) -> "failed-" + batch,
        null);

    assertThat(results).containsExactly("failed-1", "failed-2");
  }

  private AnalysisBatchScheduler scheduler(BoundedExecutor executor) {
    return new AnalysisBatchScheduler(executor);
  }

  private BoundedExecutor executor(int poolSize, int queueCapacity) {
    BoundedExecutor executor = TestBoundedExecutor.create(poolSize, queueCapacity);
    executors.add(executor);
    return executor;
  }
}
