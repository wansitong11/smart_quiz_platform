package interview.guide.common.async;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import org.slf4j.MDC;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 命名有界执行器的薄契约：提交任务、批量提交、按完成顺序收结果，并把队列满翻译成
 * {@link ExecutorRejectedException}。
 *
 * <p>本类不创建线程池；线程池由 {@link BoundedExecutorFactory} 统一构建，保证线程命名、
 * 有界队列、拒绝策略、指标与上下文传播只实现一次。
 */
public final class BoundedExecutor implements Executor, AutoCloseable {

  private final String name;
  private final ThreadPoolExecutor pool;
  private final ExecutorService delegate;
  private final boolean propagateContext;
  private final boolean propagateAiCallContext;

  BoundedExecutor(String name, ThreadPoolExecutor pool, boolean propagateContext,
                  boolean propagateAiCallContext) {
    this.name = name;
    this.pool = pool;
    this.propagateContext = propagateContext;
    this.propagateAiCallContext = propagateAiCallContext;
    this.delegate = propagateContext
        ? new DelegatingSecurityContextExecutorService(pool)
        : pool;
  }

  public String name() {
    return name;
  }

  @Override
  public void execute(Runnable command) {
    try {
      delegate.execute(wrap(command));
    } catch (RejectedExecutionException e) {
      throw new ExecutorRejectedException(name, e);
    }
  }

  public Future<?> submit(Runnable task) {
    try {
      return delegate.submit(wrap(task));
    } catch (RejectedExecutionException e) {
      throw new ExecutorRejectedException(name, e);
    }
  }

  public <T> Future<T> submit(Callable<T> task) {
    try {
      return delegate.submit(wrap(task));
    } catch (RejectedExecutionException e) {
      throw new ExecutorRejectedException(name, e);
    }
  }

  /**
   * 批量提交；中途被拒绝时取消已提交的任务并把拒绝异常抛给调用方。
   * 这样调用方可以整批退避重投，而不是留下半批任务。
   */
  public <T> List<Future<T>> submitBatch(Collection<? extends Callable<T>> tasks) {
    List<Future<T>> futures = new ArrayList<>(tasks.size());
    try {
      for (Callable<T> task : tasks) {
        futures.add(submit(task));
      }
      return futures;
    } catch (RuntimeException e) {
      futures.forEach(future -> future.cancel(true));
      throw e;
    }
  }

  /**
   * 用同一执行器收「谁先完成谁先返回」的结果；提交仍走本类的上下文包装与拒绝翻译。
   */
  public <T> CompletionService<T> completionService() {
    return new ExecutorCompletionService<>(this);
  }

  public int activeCount() {
    return pool.getActiveCount();
  }

  public int queueSize() {
    return pool.getQueue().size();
  }

  public int queueRemainingCapacity() {
    return pool.getQueue().remainingCapacity();
  }

  public boolean isShutdown() {
    return pool.isShutdown();
  }

  public void shutdown() {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException e) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    shutdown();
  }

  private Runnable wrap(Runnable task) {
    if (!propagateContext && !propagateAiCallContext) {
      return task;
    }
    Map<String, String> context = propagateContext ? MDC.getCopyOfContextMap() : null;
    AiCallContext aiContext = propagateAiCallContext ? AiCallContextHolder.get() : null;
    return () -> {
      Map<String, String> previous = propagateContext ? MDC.getCopyOfContextMap() : null;
      AiCallContext previousAi = propagateAiCallContext ? AiCallContextHolder.get() : null;
      if (propagateContext) {
        restoreMdc(context);
      }
      if (propagateAiCallContext) {
        restoreAiContext(aiContext);
      }
      try {
        task.run();
      } finally {
        if (propagateContext) {
          restoreMdc(previous);
        }
        if (propagateAiCallContext) {
          restoreAiContext(previousAi);
        }
      }
    };
  }

  private <T> Callable<T> wrap(Callable<T> task) {
    if (!propagateContext && !propagateAiCallContext) {
      return task;
    }
    Map<String, String> context = propagateContext ? MDC.getCopyOfContextMap() : null;
    AiCallContext aiContext = propagateAiCallContext ? AiCallContextHolder.get() : null;
    return () -> {
      Map<String, String> previous = propagateContext ? MDC.getCopyOfContextMap() : null;
      AiCallContext previousAi = propagateAiCallContext ? AiCallContextHolder.get() : null;
      if (propagateContext) {
        restoreMdc(context);
      }
      if (propagateAiCallContext) {
        restoreAiContext(aiContext);
      }
      try {
        return task.call();
      } finally {
        if (propagateContext) {
          restoreMdc(previous);
        }
        if (propagateAiCallContext) {
          restoreAiContext(previousAi);
        }
      }
    };
  }

  private static void restoreAiContext(AiCallContext context) {
    if (context == null) {
      AiCallContextHolder.clear();
    } else {
      AiCallContextHolder.set(context);
    }
  }

  private static void restoreMdc(Map<String, String> context) {
    if (context == null || context.isEmpty()) {
      MDC.clear();
    } else {
      MDC.setContextMap(context);
    }
  }
}
