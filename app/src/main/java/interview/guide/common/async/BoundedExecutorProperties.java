package interview.guide.common.async;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 单个功能有界执行器的通用池参数。
 *
 * <p>具体功能继承本类并加自己的 {@code @ConfigurationProperties} 前缀，即可在
 * {@code application.yml} 里独立配置线程数、队列容量与拒绝策略。
 */
@Getter
@Setter
public abstract class BoundedExecutorProperties {

  @Min(1)
  @Max(32)
  private int poolSize = 2;

  @Min(1)
  private int queueCapacity = 20;

  @NotNull
  private ExecutorRejectionPolicy rejectionPolicy = ExecutorRejectionPolicy.ABORT;

  /**
   * 是否把调用线程的 SecurityContext 与 MDC 传播到 worker。
   *
   * <p>默认开启；不依赖登录上下文的热路径可显式关闭。
   */
  private boolean propagateSecurityContext = true;

  /**
   * 是否把调用线程的 AI 调用归因上下文（用户、功能、操作）传播到 worker。
   *
   * <p>默认开启；关闭后 worker 内的模型调用会按系统任务记账。
   */
  private boolean propagateAiCallContext = true;
}
