package interview.guide.common.ai;

import com.openai.errors.OpenAIServiceException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 单 vision provider 的统一准入与轻量熔断。
 *
 * <p>只对运输超时和 5xx 计熔断失败；限流、配置错误和业务解析失败不打开熔断。
 * 准入队列满时抛出 {@link VisionRequestRejectedException}，由整卷任务退避重投。
 */
@Component
public class VisionRequestGuard {

  private final VisionRequestGuardProperties properties;
  private final Semaphore inFlight;
  private final Semaphore admission;
  private final Counter rejectedCounter;
  private final Object circuitMonitor = new Object();

  private CircuitState circuitState = CircuitState.CLOSED;
  private int consecutiveCircuitFailures;
  private int halfOpenInFlight;
  private Instant circuitOpenUntil = Instant.MIN;
  private final Clock clock;

  // 多构造器场景下显式指定注入入口；另一个构造器仅供测试注入 Clock。
  @Autowired
  public VisionRequestGuard(
      VisionRequestGuardProperties properties,
      ObjectProvider<MeterRegistry> meterRegistryProvider
  ) {
    this(properties, meterRegistryProvider, Clock.systemUTC());
  }

  VisionRequestGuard(
      VisionRequestGuardProperties properties,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      Clock clock
  ) {
    this.properties = properties;
    this.clock = clock;
    this.inFlight = new Semaphore(properties.getMaxInFlight(), true);
    this.admission = new Semaphore(
        properties.getMaxInFlight() + properties.getMaxQueue(), true);

    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    this.rejectedCounter = registry == null
        ? null
        : Counter.builder("app.ai.vision.rejected")
            .description("视觉请求因准入队列满或熔断被拒绝的次数")
            .register(registry);
    if (registry != null) {
      Gauge.builder("app.ai.vision.in_flight", inFlight,
              semaphore -> properties.getMaxInFlight() - semaphore.availablePermits())
          .description("当前视觉请求并发数")
          .register(registry);
    }
  }

  public <T> T execute(Supplier<T> request) {
    if (!tryAcquire(admission)) {
      reject();
      throw new VisionRequestRejectedException("AI 识图请求排队已满，请稍后重试");
    }

    CircuitPermit permit = null;
    boolean inFlightAcquired = false;
    try {
      permit = acquireCircuitPermit();
      if (!tryAcquire(inFlight)) {
        reject();
        throw new VisionRequestRejectedException("AI 识图请求等待超时，请稍后重试");
      }
      inFlightAcquired = true;

      try {
        T result = request.get();
        recordSuccess(permit);
        return result;
      } catch (RuntimeException error) {
        recordFailure(permit, error);
        throw error;
      }
    } finally {
      if (inFlightAcquired) {
        inFlight.release();
      }
      if (permit != null) {
        permit.release();
      }
      admission.release();
    }
  }

  private boolean tryAcquire(Semaphore semaphore) {
    try {
      return semaphore.tryAcquire(
          Math.max(0, properties.getAcquireTimeout().toMillis()),
          TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VisionRequestRejectedException("AI 识图请求被中断", e);
    }
  }

  private CircuitPermit acquireCircuitPermit() {
    synchronized (circuitMonitor) {
      Instant now = clock.instant();
      if (circuitState == CircuitState.OPEN) {
        if (now.isBefore(circuitOpenUntil)) {
          reject();
          throw new VisionCircuitOpenException();
        }
        circuitState = CircuitState.HALF_OPEN;
        halfOpenInFlight = 0;
      }
      if (circuitState == CircuitState.HALF_OPEN
          && halfOpenInFlight >= properties.getCircuitHalfOpenProbes()) {
        reject();
        throw new VisionCircuitOpenException();
      }
      if (circuitState == CircuitState.HALF_OPEN) {
        halfOpenInFlight++;
      }
      return new CircuitPermit(circuitState == CircuitState.HALF_OPEN);
    }
  }

  private void recordSuccess(CircuitPermit permit) {
    synchronized (circuitMonitor) {
      if (!permit.halfOpen()) {
        consecutiveCircuitFailures = 0;
        return;
      }
      closeCircuit();
    }
  }

  private void recordFailure(CircuitPermit permit, Throwable error) {
    if (!isCircuitFailure(error)) {
      if (permit.halfOpen()) {
        synchronized (circuitMonitor) {
          closeCircuit();
        }
      }
      return;
    }

    synchronized (circuitMonitor) {
      if (permit.halfOpen() || circuitState == CircuitState.HALF_OPEN) {
        openCircuit();
        return;
      }
      consecutiveCircuitFailures++;
      if (consecutiveCircuitFailures >= properties.getCircuitFailureThreshold()) {
        openCircuit();
      }
    }
  }

  private void closeCircuit() {
    circuitState = CircuitState.CLOSED;
    consecutiveCircuitFailures = 0;
    halfOpenInFlight = 0;
    circuitOpenUntil = Instant.MIN;
  }

  private void openCircuit() {
    circuitState = CircuitState.OPEN;
    halfOpenInFlight = 0;
    circuitOpenUntil = clock.instant().plus(properties.getCircuitOpenDuration());
  }

  static boolean isCircuitFailure(Throwable error) {
    if (StructuredOutputInvoker.isTransportFailure(error)) {
      return true;
    }
    for (Throwable current = error; current != null; current = nextCause(current)) {
      if (current instanceof OpenAIServiceException serviceError
          && serviceError.statusCode() >= 500) {
        return true;
      }
    }
    return false;
  }

  private static Throwable nextCause(Throwable error) {
    Throwable cause = error.getCause();
    return cause == error ? null : cause;
  }

  private void reject() {
    if (rejectedCounter != null) {
      rejectedCounter.increment();
    }
  }

  private enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final class CircuitPermit {

    private final boolean halfOpen;

    private CircuitPermit(boolean halfOpen) {
      this.halfOpen = halfOpen;
    }

    private boolean halfOpen() {
      return halfOpen;
    }

    private void release() {
      if (!halfOpen) {
        return;
      }
      synchronized (circuitMonitor) {
        halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
      }
    }
  }
}
