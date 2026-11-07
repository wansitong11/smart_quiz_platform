package interview.guide.common.async;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试用有界执行器工厂，避免每个测试重复搭线程池。
 */
public final class TestBoundedExecutor {

  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  private TestBoundedExecutor() {
  }

  public static BoundedExecutor create(int poolSize, int queueCapacity) {
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    BoundedExecutorProperties properties = new BoundedExecutorProperties() {
    };
    properties.setPoolSize(poolSize);
    properties.setQueueCapacity(queueCapacity);
    properties.setPropagateSecurityContext(false);
    properties.setPropagateAiCallContext(false);
    return new BoundedExecutorFactory(provider)
        .create("test-" + SEQUENCE.incrementAndGet(), properties);
  }
}
