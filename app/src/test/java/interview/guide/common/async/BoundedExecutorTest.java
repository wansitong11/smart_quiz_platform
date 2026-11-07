package interview.guide.common.async;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiFeature;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@DisplayName("通用有界执行器")
class BoundedExecutorTest {

  private BoundedExecutorFactory factory;
  private final List<BoundedExecutor> executors = new ArrayList<>();

  @BeforeEach
  void setUp() {
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    factory = new BoundedExecutorFactory(provider);
  }

  @AfterEach
  void tearDown() {
    executors.forEach(BoundedExecutor::shutdown);
    SecurityContextHolder.clearContext();
    AiCallContextHolder.clear();
    MDC.clear();
  }

  private BoundedExecutor executor(String name, int poolSize, int queueCapacity,
                                   boolean propagateContext) {
    return executor(name, poolSize, queueCapacity, propagateContext, true);
  }

  private BoundedExecutor executor(String name, int poolSize, int queueCapacity,
                                   boolean propagateContext, boolean propagateAiCallContext) {
    BoundedExecutorProperties properties = new BoundedExecutorProperties() {
    };
    properties.setPoolSize(poolSize);
    properties.setQueueCapacity(queueCapacity);
    properties.setPropagateSecurityContext(propagateContext);
    properties.setPropagateAiCallContext(propagateAiCallContext);
    BoundedExecutor executor = factory.create(name, properties);
    executors.add(executor);
    return executor;
  }

  @Test
  @DisplayName("提交任务并拿到结果")
  void submitsTask() throws Exception {
    BoundedExecutor executor = executor("submit", 1, 1, false);

    Future<String> future = executor.submit(() -> "ok");

    assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("ok");
  }

  @Test
  @DisplayName("队列满时抛出 ExecutorRejectedException")
  void rejectsWhenQueueFull() throws Exception {
    BoundedExecutor executor = executor("reject", 1, 1, false);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    executor.submit(() -> {
      started.countDown();
      release.await();
      return "running";
    });
    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
    executor.submit(() -> "queued");

    assertThatThrownBy(() -> executor.submit(() -> "rejected"))
        .isInstanceOf(ExecutorRejectedException.class)
        .isInstanceOfSatisfying(ExecutorRejectedException.class,
            error -> assertThat(error.executorName()).isEqualTo("reject"));
    release.countDown();
  }

  @Test
  @DisplayName("CompletionService 能收齐全部页结果")
  void completionServiceCollectsResults() throws Exception {
    BoundedExecutor executor = executor("completion", 3, 10, false);
    CompletionService<String> completionService = executor.completionService();

    completionService.submit(() -> "one");
    completionService.submit(() -> "two");
    completionService.submit(() -> "three");

    List<String> results = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      Future<String> future = completionService.poll(1, TimeUnit.SECONDS);
      assertThat(future).isNotNull();
      results.add(future.get());
    }
    assertThat(results).containsExactlyInAnyOrder("one", "two", "three");
  }

  @Test
  @DisplayName("默认把 SecurityContext 传播到 worker")
  void propagatesSecurityContext() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
    BoundedExecutor executor = executor("security-on", 1, 1, true);

    String name = executor.submit(() -> {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      return authentication == null ? null : authentication.getName();
    }).get(1, TimeUnit.SECONDS);

    assertThat(name).isEqualTo("alice");
  }

  @Test
  @DisplayName("关闭传播后 worker 看不到 SecurityContext")
  void doesNotPropagateSecurityContextWhenDisabled() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
    BoundedExecutor executor = executor("security-off", 1, 1, false);

    String name = executor.submit(() -> {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      return authentication == null ? null : authentication.getName();
    }).get(1, TimeUnit.SECONDS);

    assertThat(name).isNull();
  }

  @Test
  @DisplayName("把 MDC 传播到 worker")
  void propagatesMdc() throws Exception {
    MDC.put("traceId", "trace-123");
    BoundedExecutor executor = executor("mdc-on", 1, 1, true);

    String traceId = executor.submit(() -> MDC.get("traceId")).get(1, TimeUnit.SECONDS);

    assertThat(traceId).isEqualTo("trace-123");
  }

  @Test
  @DisplayName("关闭传播后 worker 看不到 MDC")
  void doesNotPropagateMdcWhenDisabled() throws Exception {
    MDC.put("traceId", "trace-123");
    BoundedExecutor executor = executor("mdc-off", 1, 1, false);

    String traceId = executor.submit(() -> MDC.get("traceId")).get(1, TimeUnit.SECONDS);

    assertThat(traceId).isNull();
  }

  @Test
  @DisplayName("把 AiCallContext 传播到 worker")
  void propagatesAiCallContext() throws Exception {
    AiCallContextHolder.AiCallContext context = new AiCallContextHolder.AiCallContext(
        42L, AiFeature.PAPER_IMPORT, "page-recognize", "req-1", "op-1", "async");
    AiCallContextHolder.set(context);
    BoundedExecutor executor = executor("ai-on", 1, 1, false, true);

    AiCallContextHolder.AiCallContext seen = executor
        .submit(AiCallContextHolder::get)
        .get(1, TimeUnit.SECONDS);

    assertThat(seen).isEqualTo(context);
  }

  @Test
  @DisplayName("关闭传播后 worker 看不到 AiCallContext")
  void doesNotPropagateAiCallContextWhenDisabled() throws Exception {
    AiCallContextHolder.set(new AiCallContextHolder.AiCallContext(
        42L, AiFeature.PAPER_IMPORT, "page-recognize", "req-1", "op-1", "async"));
    BoundedExecutor executor = executor("ai-off", 1, 1, false, false);

    AiCallContextHolder.AiCallContext seen = executor
        .submit(AiCallContextHolder::get)
        .get(1, TimeUnit.SECONDS);

    assertThat(seen).isNull();
  }

  @Test
  @DisplayName("任务结束后不把 AiCallContext 泄漏给下一个任务")
  void doesNotLeakAiCallContextToNextTask() throws Exception {
    AiCallContextHolder.set(new AiCallContextHolder.AiCallContext(
        42L, AiFeature.PAPER_IMPORT, "page-recognize", "req-1", "op-1", "async"));
    BoundedExecutor executor = executor("ai-leak", 1, 1, false, true);

    assertThat(executor.submit(AiCallContextHolder::get).get(1, TimeUnit.SECONDS))
        .isNotNull();
    AiCallContextHolder.clear();
    assertThat(executor.submit(AiCallContextHolder::get).get(1, TimeUnit.SECONDS))
        .isNull();
  }

  @Test
  @DisplayName("注册活跃线程、队列与拒绝指标")
  void registersMetricsAndCountsRejections() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    BoundedExecutorFactory metricsFactory = new BoundedExecutorFactory(provider);
    BoundedExecutorProperties properties = new BoundedExecutorProperties() {
    };
    properties.setPoolSize(1);
    properties.setQueueCapacity(1);
    BoundedExecutor executor = metricsFactory.create("metrics", properties);
    executors.add(executor);

    assertThat(registry.find("app.executor.active").tag("executor", "metrics").gauge())
        .isNotNull();
    assertThat(registry.find("app.executor.queue.size").tag("executor", "metrics").gauge())
        .isNotNull();
    assertThat(registry.find("app.executor.queue.remaining").tag("executor", "metrics").gauge())
        .isNotNull();

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    executor.submit(() -> {
      started.countDown();
      release.await();
      return null;
    });
    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
    executor.submit(() -> null);
    assertThatThrownBy(() -> executor.submit(() -> null))
        .isInstanceOf(ExecutorRejectedException.class);

    assertThat(registry.counter("app.executor.rejected", "executor", "metrics").count())
        .isEqualTo(1.0);
    release.countDown();
  }

  @Test
  @DisplayName("同一名称不能重复创建")
  void rejectsDuplicateName() {
    executor("duplicate", 1, 1, false);
    BoundedExecutorProperties properties = new BoundedExecutorProperties() {
    };

    assertThatThrownBy(() -> factory.create("duplicate", properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate");
  }
}
