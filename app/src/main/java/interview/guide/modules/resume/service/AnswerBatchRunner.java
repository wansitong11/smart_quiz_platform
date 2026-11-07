package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.ExecutorRejectedException;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.resume.log.PaperFlowLog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * 答案键批次执行器：统一提交、拒绝降级、失败取消与路由校验。
 */
@Component
public class AnswerBatchRunner {

  private static final int MAX_EMPTY_SUBMIT_ROUNDS = 50;
  private static final long BACKPRESSURE_SLEEP_MS = 200L;

  private final BoundedExecutor executor;

  public AnswerBatchRunner(@Qualifier("paperAnswerExecutor") BoundedExecutor executor) {
    this.executor = executor;
  }

  public List<BatchOutcome> run(List<BatchTask> tasks, CancelPolicy cancelPolicy) {
    return run(tasks, cancelPolicy, null);
  }

  public List<BatchOutcome> run(List<BatchTask> tasks, CancelPolicy cancelPolicy,
                                Runnable heartbeat) {
    return run(tasks, cancelPolicy, heartbeat, null);
  }

  public List<BatchOutcome> run(List<BatchTask> tasks, CancelPolicy cancelPolicy,
                                Runnable heartbeat, Consumer<BatchOutcome> outcomeListener) {
    if (tasks == null || tasks.isEmpty()) {
      return List.of();
    }
    List<BatchOutcome> outcomes = new ArrayList<>(tasks.size());
    int submitted = 0;
    int emptyRounds = 0;
    while (submitted < tasks.size()) {
      List<Future<BatchOutcome>> futures = new ArrayList<>();
      List<BatchTask> roundTasks = new ArrayList<>();
      while (submitted < tasks.size()) {
        BatchTask task = tasks.get(submitted);
        try {
          futures.add(executor.submit(task.body()));
          roundTasks.add(task);
          submitted++;
        } catch (ExecutorRejectedException e) {
          PaperFlowLog.warn("ANSWER_BATCH_SUBMIT_REJECTED executor={}, submitted={}, "
                  + "active={}, queue={}",
              executor.name(), submitted, executor.activeCount(), executor.queueSize());
          break;
        }
      }

      if (futures.isEmpty()) {
        if (executor.isShutdown() || emptyRounds >= MAX_EMPTY_SUBMIT_ROUNDS) {
          for (; submitted < tasks.size(); submitted++) {
            BatchOutcome outcome = BatchOutcome.failure(
                tasks.get(submitted).phase(), tasks.get(submitted).batchIndex(),
                "答案键批次等待执行器超时，本轮跳过，稍后按缺失题重试");
            outcomes.add(outcome);
            notifyOutcome(outcomeListener, outcome);
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
        BatchOutcome outcome = await(futures.get(i), roundTasks.get(i));
        outcomes.add(outcome);
        notifyOutcome(outcomeListener, outcome);
        runHeartbeat(heartbeat);
        if (cancelPolicy == CancelPolicy.CANCEL_ON_FATAL && outcome.fatal() != null) {
          cancel(futures);
          throw rethrow(outcome.fatal());
        }
      }
    }
    return outcomes;
  }

  private void notifyOutcome(Consumer<BatchOutcome> listener, BatchOutcome outcome) {
    if (listener != null) {
      listener.accept(outcome);
    }
  }

  private void sleepBackpressure() {
    try {
      Thread.sleep(BACKPRESSURE_SLEEP_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
          "答案键批次等待执行器被中断，请稍后重试", e);
    }
  }

  private void runHeartbeat(Runnable heartbeat) {
    if (heartbeat != null) {
      heartbeat.run();
    }
  }

  private BatchOutcome await(Future<BatchOutcome> future, BatchTask task) {
    try {
      BatchOutcome outcome = future.get();
      validate(task, outcome);
      return outcome;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
          "答案键批次等待被中断，请稍后重试", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      return new BatchOutcome(task.phase(), task.batchIndex(), Map.of(), List.of(), cause);
    }
  }

  private void validate(BatchTask task, BatchOutcome outcome) {
    if (outcome == null) {
      throw new IllegalStateException("答案键批次返回为空: phase=" + task.phase()
          + ", batchIndex=" + task.batchIndex());
    }
    if (outcome.phase() != task.phase() || outcome.batchIndex() != task.batchIndex()) {
      throw new IllegalStateException("答案键批次路由不匹配: expected phase=" + task.phase()
          + ", batchIndex=" + task.batchIndex() + ", actual phase=" + outcome.phase()
          + ", batchIndex=" + outcome.batchIndex());
    }
  }

  private static void cancel(List<Future<BatchOutcome>> futures) {
    futures.forEach(future -> future.cancel(true));
  }

  private static RuntimeException rethrow(Throwable throwable) {
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    return new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
        "答案键生成失败", throwable);
  }

  public record BatchTask(AnswerPhase phase, int batchIndex, Callable<BatchOutcome> body) {
  }

  public record BatchOutcome(AnswerPhase phase, int batchIndex,
                             Map<Integer, AnswerKeyDTO> keyDelta,
                             List<String> warnings, Throwable fatal) {

    public static BatchOutcome success(AnswerPhase phase, int batchIndex,
                                       Map<Integer, AnswerKeyDTO> keyDelta,
                                       List<String> warnings) {
      return new BatchOutcome(phase, batchIndex,
          keyDelta == null ? Map.of() : Map.copyOf(keyDelta),
          warnings == null ? List.of() : List.copyOf(warnings), null);
    }

    public static BatchOutcome failure(AnswerPhase phase, int batchIndex, String warning) {
      return new BatchOutcome(phase, batchIndex, Map.of(),
          warning == null ? List.of() : List.of(warning), null);
    }

    public static BatchOutcome fatal(AnswerPhase phase, int batchIndex, Throwable fatal) {
      return new BatchOutcome(phase, batchIndex, Map.of(), List.of(), fatal);
    }
  }

  public enum CancelPolicy {
    CANCEL_ON_FATAL,
    IGNORE_FATAL
  }
}
