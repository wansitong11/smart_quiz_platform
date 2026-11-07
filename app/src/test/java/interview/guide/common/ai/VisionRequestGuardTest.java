package interview.guide.common.ai;

import com.openai.errors.OpenAIIoException;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("视觉 Provider 请求保护器")
class VisionRequestGuardTest {

  private final ExecutorService callers = new ThreadPoolExecutor(
      3, 3, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(10));

  @AfterEach
  void tearDown() {
    callers.shutdownNow();
  }

  @Test
  @DisplayName("并发请求不会超过 max-in-flight")
  void limitsConcurrentRequests() throws Exception {
    VisionRequestGuardProperties properties = properties(1, 2);
    VisionRequestGuard guard = guard(properties);
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();

    Future<String> first = callers.submit(() -> guard.execute(() -> {
      int current = active.incrementAndGet();
      peak.accumulateAndGet(current, Math::max);
      firstStarted.countDown();
      await(releaseFirst);
      active.decrementAndGet();
      return "first";
    }));
    assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
    Future<String> second = callers.submit(() -> guard.execute(() -> {
      int current = active.incrementAndGet();
      peak.accumulateAndGet(current, Math::max);
      active.decrementAndGet();
      return "second";
    }));

    Thread.sleep(50);
    assertThat(peak.get()).isEqualTo(1);
    releaseFirst.countDown();
    assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
    assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo("second");
  }

  @Test
  @DisplayName("等待队列满时快速拒绝，由整卷退避重投")
  void rejectsWhenAdmissionQueueIsFull() throws Exception {
    VisionRequestGuardProperties properties = properties(1, 0);
    properties.setAcquireTimeout(Duration.ofMillis(30));
    VisionRequestGuard guard = guard(properties);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<String> running = callers.submit(() -> guard.execute(() -> {
      started.countDown();
      await(release);
      return "running";
    }));
    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> guard.execute(() -> "rejected"))
        .isInstanceOf(VisionRequestRejectedException.class);

    release.countDown();
    assertThat(running.get(1, TimeUnit.SECONDS)).isEqualTo("running");
  }

  @Test
  @DisplayName("运输超时连续发生达到阈值后熔断")
  void opensCircuitAfterTransientFailures() {
    VisionRequestGuardProperties properties = properties(2, 2);
    properties.setCircuitFailureThreshold(2);
    properties.setCircuitOpenDuration(Duration.ofMinutes(1));
    VisionRequestGuard guard = guard(properties);
    AtomicInteger calls = new AtomicInteger();

    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> guard.execute(() -> {
        calls.incrementAndGet();
        throw new OpenAIIoException("timeout");
      })).isInstanceOf(OpenAIIoException.class);
    }

    assertThatThrownBy(() -> guard.execute(() -> {
      calls.incrementAndGet();
      return "unexpected";
    })).isInstanceOf(VisionCircuitOpenException.class);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("非运输类失败不会触发熔断")
  void doesNotOpenCircuitForTerminalFailure() {
    VisionRequestGuardProperties properties = properties(2, 2);
    properties.setCircuitFailureThreshold(1);
    VisionRequestGuard guard = guard(properties);
    AtomicInteger calls = new AtomicInteger();

    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> guard.execute(() -> {
        calls.incrementAndGet();
        throw new IllegalArgumentException("model not found");
      })).isInstanceOf(IllegalArgumentException.class);
    }

    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  @DisplayName("冷却后半开探测成功会关闭熔断")
  void closesCircuitAfterHalfOpenProbeSucceeds() throws Exception {
    VisionRequestGuardProperties properties = properties(1, 1);
    properties.setCircuitFailureThreshold(1);
    properties.setCircuitOpenDuration(Duration.ofMillis(20));
    VisionRequestGuard guard = guard(properties);

    assertThatThrownBy(() -> guard.execute(() -> {
      throw new OpenAIIoException("timeout");
    })).isInstanceOf(OpenAIIoException.class);
    Thread.sleep(40);

    assertThat(guard.execute(() -> "recovered")).isEqualTo("recovered");
    assertThat(guard.execute(() -> "closed")).isEqualTo("closed");
  }

  @Test
  @DisplayName("Spring 容器可以通过公开构造器创建实例")
  void createsBeanThroughSpring() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(VisionRequestGuardProperties.class, VisionRequestGuardProperties::new);
      context.register(VisionRequestGuard.class);
      context.refresh();

      assertThat(context.getBean(VisionRequestGuard.class)).isNotNull();
    }
  }

  private VisionRequestGuardProperties properties(int maxInFlight, int maxQueue) {
    VisionRequestGuardProperties properties = new VisionRequestGuardProperties();
    properties.setMaxInFlight(maxInFlight);
    properties.setMaxQueue(maxQueue);
    properties.setAcquireTimeout(Duration.ofSeconds(1));
    return properties;
  }

  private VisionRequestGuard guard(VisionRequestGuardProperties properties) {
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    return new VisionRequestGuard(properties, provider);
  }

  private void await(CountDownLatch latch) {
    try {
      latch.await(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
