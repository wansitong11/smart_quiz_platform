package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.TestBoundedExecutor;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.AnswerKeyDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("答案键批次执行器")
class AnswerBatchRunnerTest {

  private final List<BoundedExecutor> executors = new ArrayList<>();

  @AfterEach
  void tearDown() {
    executors.forEach(BoundedExecutor::shutdown);
  }

  @Test
  @DisplayName("批次返回的阶段或批次号不一致时拒绝归位")
  void shouldRejectMismatchedRoute() {
    AnswerBatchRunner runner = runner(2, 5);
    List<AnswerBatchRunner.BatchTask> tasks = List.of(
        new AnswerBatchRunner.BatchTask(AnswerPhase.PRIMARY, 3,
            () -> AnswerBatchRunner.BatchOutcome.success(
                AnswerPhase.VERIFY, 3, Map.of(), List.of()))
    );

    assertThatThrownBy(() -> runner.run(
        tasks, AnswerBatchRunner.CancelPolicy.CANCEL_ON_FATAL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("路由不匹配");
  }

  @Test
  @DisplayName("主求解批次致命失败时原样上抛")
  void shouldRethrowPrimaryFatalFailure() {
    AnswerBatchRunner runner = runner(2, 5);
    BusinessException fatal = new BusinessException(
        ErrorCode.PAPER_EXTRACTION_FAILED, "主求解失败");
    List<AnswerBatchRunner.BatchTask> tasks = List.of(
        new AnswerBatchRunner.BatchTask(AnswerPhase.PRIMARY, 0,
            () -> AnswerBatchRunner.BatchOutcome.fatal(
                AnswerPhase.PRIMARY, 0, fatal))
    );

    assertThatThrownBy(() -> runner.run(
        tasks, AnswerBatchRunner.CancelPolicy.CANCEL_ON_FATAL))
        .isSameAs(fatal);
  }

  @Test
  @DisplayName("主求解致命失败时取消其余在跑批次")
  void shouldCancelRunningBatchesAfterFatalFailure() throws Exception {
    AnswerBatchRunner runner = runner(2, 5);
    CountDownLatch siblingStarted = new CountDownLatch(1);
    CountDownLatch siblingInterrupted = new CountDownLatch(1);
    BusinessException fatal = new BusinessException(
        ErrorCode.PAPER_EXTRACTION_FAILED, "主求解失败");
    List<AnswerBatchRunner.BatchTask> tasks = List.of(
        new AnswerBatchRunner.BatchTask(AnswerPhase.PRIMARY, 0, () -> {
          assertThat(siblingStarted.await(1, TimeUnit.SECONDS)).isTrue();
          return AnswerBatchRunner.BatchOutcome.fatal(AnswerPhase.PRIMARY, 0, fatal);
        }),
        new AnswerBatchRunner.BatchTask(AnswerPhase.VERIFY, 1, () -> {
          siblingStarted.countDown();
          try {
            TimeUnit.SECONDS.sleep(10);
          } catch (InterruptedException e) {
            siblingInterrupted.countDown();
            throw e;
          }
          return AnswerBatchRunner.BatchOutcome.success(
              AnswerPhase.VERIFY, 1, Map.of(), List.of());
        })
    );

    assertThatThrownBy(() -> runner.run(
        tasks, AnswerBatchRunner.CancelPolicy.CANCEL_ON_FATAL))
        .isSameAs(fatal);
    assertThat(siblingInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("复核批次非致命失败只返回警告")
  void shouldReturnWarningForVerifyFailure() {
    AnswerBatchRunner runner = runner(2, 5);
    List<AnswerBatchRunner.BatchTask> tasks = List.of(
        new AnswerBatchRunner.BatchTask(AnswerPhase.VERIFY, 1,
            () -> AnswerBatchRunner.BatchOutcome.failure(
                AnswerPhase.VERIFY, 1, "复核失败，已保留主答案"))
    );

    List<AnswerBatchRunner.BatchOutcome> outcomes = runner.run(
        tasks, AnswerBatchRunner.CancelPolicy.CANCEL_ON_FATAL);

    assertThat(outcomes).hasSize(1);
    assertThat(outcomes.getFirst().fatal()).isNull();
    assertThat(outcomes.getFirst().warnings())
        .containsExactly("复核失败，已保留主答案");
  }

  @Test
  @DisplayName("执行器队列满时等待在途批次完成后再提交，不回退当前线程")
  void shouldApplyBackpressureWhenRejected() throws Exception {
    AnswerBatchRunner runner = runner(1, 1);
    AtomicReference<String> rejectedTaskThread = new AtomicReference<>();
    List<AnswerBatchRunner.BatchTask> tasks = List.of(
        task(0, () -> {
          sleep(150);
          return Map.of();
        }),
        task(1, () -> {
          sleep(10);
          return Map.of();
        }),
        task(2, () -> {
          rejectedTaskThread.set(Thread.currentThread().getName());
          return Map.of();
        })
    );

    List<AnswerBatchRunner.BatchOutcome> outcomes = runner.run(
        tasks, AnswerBatchRunner.CancelPolicy.CANCEL_ON_FATAL);

    assertThat(outcomes).hasSize(3);
    assertThat(outcomes).extracting(AnswerBatchRunner.BatchOutcome::batchIndex)
        .containsExactly(0, 1, 2);
    assertThat(rejectedTaskThread.get()).isNotNull();
    assertThat(rejectedTaskThread.get()).isNotEqualTo(Thread.currentThread().getName());
  }

  private AnswerBatchRunner.BatchTask task(
      int batchIndex,
      Callable<Map<Integer, AnswerKeyDTO>> body
  ) {
    return new AnswerBatchRunner.BatchTask(AnswerPhase.PRIMARY, batchIndex, () ->
        AnswerBatchRunner.BatchOutcome.success(
            AnswerPhase.PRIMARY, batchIndex, body.call(), List.of()));
  }

  private AnswerBatchRunner runner(int poolSize, int queueCapacity) {
    BoundedExecutor executor = TestBoundedExecutor.create(poolSize, queueCapacity);
    executors.add(executor);
    return new AnswerBatchRunner(executor);
  }

  private static void sleep(long millis) throws InterruptedException {
    TimeUnit.MILLISECONDS.sleep(millis);
  }
}
