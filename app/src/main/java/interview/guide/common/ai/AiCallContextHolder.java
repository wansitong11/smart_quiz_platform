package interview.guide.common.ai;

/**
 * 单次模型调用的归因上下文（ThreadLocal）。
 *
 * <p>同步请求由 {@code AiCostAspect} 写入；异步任务由 {@code AbstractStreamProducer}
 * 在入队线程读取后随消息透传，消费者在 worker 线程回填。读取不到时视为系统任务。</p>
 */
public final class AiCallContextHolder {

  private static final ThreadLocal<AiCallContext> HOLDER = new ThreadLocal<>();

  private AiCallContextHolder() {
  }

  public static void set(AiCallContext context) {
    HOLDER.set(context);
  }

  public static AiCallContext get() {
    return HOLDER.get();
  }

  public static void clear() {
    HOLDER.remove();
  }

  public static <T> T callWith(AiCallContext context, ThrowingSupplier<T> action) throws Throwable {
    AiCallContext previous = HOLDER.get();
    HOLDER.set(context);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        HOLDER.remove();
      } else {
        HOLDER.set(previous);
      }
    }
  }

  public static void runWith(AiCallContext context, Runnable action) {
    AiCallContext previous = HOLDER.get();
    HOLDER.set(context);
    try {
      action.run();
    } finally {
      if (previous == null) {
        HOLDER.remove();
      } else {
        HOLDER.set(previous);
      }
    }
  }

  public record AiCallContext(
      Long userId,
      AiFeature feature,
      String operation,
      String requestId,
      String operationId,
      String source
  ) {
  }

  @FunctionalInterface
  public interface ThrowingSupplier<T> {
    T get() throws Throwable;
  }
}
