package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.ExecutorRejectedException;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.log.PaperFlowLog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 解析批次调度：正常批并发提交，缺失补跑顺序提交。
 */
@Component
public class AnalysisBatchScheduler {

  private static final int MAX_EMPTY_SUBMIT_ROUNDS = 50;
  private static final long BACKPRESSURE_SLEEP_MS = 200L;

  private final BoundedExecutor executor;

  public AnalysisBatchScheduler(
      @Qualifier("paperAnswerExecutor") BoundedExecutor executor) {
    this.executor = executor;
  }

  public <B, R> List<R> runBatches(
      List<B> batches,
      Function<B, Callable<R>> taskFactory,
      BiFunction<B, Throwable, R> failureHandler,
      Runnable heartbeat) {
    if (batches == null || batches.isEmpty()) {
      return List.of();
    }
    List<R> results = new ArrayList<>(batches.size());
    int submitted = 0;
    int emptyRounds = 0;
    while (submitted < batches.size()) {
      List<Future<R>> futures = new ArrayList<>();
      List<B> roundBatches = new ArrayList<>();
      while (submitted < batches.size()) {
        B batch = batches.get(submitted);
        try {
          futures.add(executor.submit(taskFactory.apply(batch)));
          roundBatches.add(batch);
          submitted++;
        } catch (ExecutorRejectedException e) {
          PaperFlowLog.warn("ANSWER_ANALYSIS_SUBMIT_REJECTED executor={}, submitted={}, "
                  + "active={}, queue={}",
              executor.name(), submitted, executor.activeCount(), executor.queueSize());
          break;
        }
      }

      if (futures.isEmpty()) {
        if (executor.isShutdown() || emptyRounds >= MAX_EMPTY_SUBMIT_ROUNDS) {
          BusinessException exhausted = new BusinessException(
              ErrorCode.PAPER_EXTRACTION_FAILED, "解析批次等待执行器超时，已保留标准答案");
          for (; submitted < batches.size(); submitted++) {
            results.add(failureHandler.apply(batches.get(submitted), exhausted));
            runHeartbeat(heartbeat);
          }
          break;
        }
        emptyRounds++;
        sleepBackpressure();
        continue;
      }

      emptyRounds = 0;
      for (int i = 0; i < futures.size(); i++) {
        results.add(await(futures.get(i), roundBatches.get(i), failureHandler));
        runHeartbeat(heartbeat);
      }
    }
    return results;
  }

  public <B, R> List<R> runRecovery(
      List<B> batches,
      Function<B, Callable<R>> taskFactory,
      BiFunction<B, Throwable, R> failureHandler,
      Runnable heartbeat,
      long deadlineEpochMs) {
    if (batches == null || batches.isEmpty()) {
      return List.of();
    }
    List<R> results = new ArrayList<>(batches.size());
    for (int i = 0; i < batches.size(); i++) {
      B batch = batches.get(i);
      if (System.currentTimeMillis() >= deadlineEpochMs) {
        BusinessException timeout = new BusinessException(
            ErrorCode.PAPER_EXTRACTION_FAILED, "解析补跑等待预算耗尽");
        for (int j = i; j < batches.size(); j++) {
          results.add(failureHandler.apply(batches.get(j), timeout));
          runHeartbeat(heartbeat);
        }
        break;
      }
      Future<R> future;
      try {
        future = executor.submit(taskFactory.apply(batch));
      } catch (ExecutorRejectedException e) {
        results.add(failureHandler.apply(batch, e));
        runHeartbeat(heartbeat);
        continue;
      }
      results.add(await(future, batch, failureHandler));
      runHeartbeat(heartbeat);
    }
    return results;
  }

  private <B, R> R await(Future<R> future, B batch,
                         BiFunction<B, Throwable, R> failureHandler) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
          "题目解析等待被中断，请稍后重试", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      return failureHandler.apply(batch, cause);
    }
  }

  private void runHeartbeat(Runnable heartbeat) {
    if (heartbeat != null) {
      heartbeat.run();
    }
  }

  private void sleepBackpressure() {
    try {
      Thread.sleep(BACKPRESSURE_SLEEP_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
          "解析批次等待执行器被中断，请稍后重试", e);
    }
  }
}
