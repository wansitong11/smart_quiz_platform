package interview.guide.common.async;

import interview.guide.common.ai.AiCostControl;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Stream 消费者并发执行")
class AbstractStreamConsumerConcurrencyTest {

  @Test
  @DisplayName("同一 Stream 的两条消息可以同时执行")
  void processesTwoMessagesConcurrently() throws Exception {
    Fixture fixture = new Fixture(false);
    CountDownLatch release = new CountDownLatch(1);
    fixture.deliverTwoMessages();
    fixture.consumer.blockUntilReleased(release);

    fixture.consumer.init();

    assertThat(fixture.consumer.bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    verify(fixture.redis, timeout(2000).times(2))
        .streamAck(anyString(), anyString(), any(StreamMessageId.class));
    fixture.close();
  }

  @Test
  @DisplayName("一条消息失败不影响另一条消息 ACK")
  void isolatesFailurePerMessage() throws Exception {
    Fixture fixture = new Fixture(true);
    fixture.deliverTwoMessages();

    fixture.consumer.init();

    verify(fixture.redis, timeout(2000).times(2))
        .streamAck(anyString(), anyString(), any(StreamMessageId.class));
    assertThat(fixture.consumer.failureCount.get()).isEqualTo(1);
    fixture.close();
  }

  @Test
  @DisplayName("终态写入失败时不 ACK")
  void doesNotAckWhenTerminalStateWriteFails() throws Exception {
    Fixture fixture = new Fixture(false);
    fixture.consumer.failTerminalState = true;
    AiCostControl aiCostControl = mock(AiCostControl.class);
    fixture.consumer.setAiCostControl(aiCostControl);
    fixture.deliverSingleMessage("1", Map.of(
        AsyncTaskStreamConstants.FIELD_OWNER_ID, "7",
        AsyncTaskStreamConstants.FIELD_AI_FEATURE, "KB_QA",
        AsyncTaskStreamConstants.FIELD_AI_OPERATION_ID, "op-1"
    ));

    fixture.consumer.init();

    assertThat(fixture.consumer.terminalStateAttempted.await(2, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(250);
    verify(fixture.redis, never())
        .streamAck(anyString(), anyString(), any(StreamMessageId.class));
    verify(aiCostControl, never()).finalize(anyString());
    fixture.close();
  }

  @Test
  @DisplayName("瞬态业务异常走重投，不直接落失败")
  void retriesTransientBusinessFailure() throws Exception {
    Fixture fixture = new Fixture(true, true);
    fixture.deliverSingleMessage("1");

    fixture.consumer.init();

    verify(fixture.redis, timeout(2000))
        .streamAck(anyString(), anyString(), any(StreamMessageId.class));
    assertThat(fixture.consumer.retryCount.get()).isEqualTo(1);
    assertThat(fixture.consumer.failureCount.get()).isZero();
    fixture.close();
  }

  @Test
  @DisplayName("中间阶段成功时把预算结算交给下游")
  void keepsBudgetReservedWhenIntermediateStageSucceeds() throws Exception {
    Fixture fixture = new Fixture(false, false, false);
    AiCostControl aiCostControl = mock(AiCostControl.class);
    fixture.consumer.setAiCostControl(aiCostControl);
    fixture.deliverSingleMessage("1", Map.of(
        AsyncTaskStreamConstants.FIELD_OWNER_ID, "7",
        AsyncTaskStreamConstants.FIELD_AI_FEATURE, "PAPER_IMPORT",
        AsyncTaskStreamConstants.FIELD_AI_OPERATION_ID, "op-1"
    ));

    fixture.consumer.init();

    verify(fixture.redis, timeout(2000))
        .streamAck(anyString(), anyString(), any(StreamMessageId.class));
    verify(aiCostControl, never()).finalize(anyString());
    fixture.close();
  }

  @Test
  @DisplayName("中间阶段终态失败时仍然释放预算")
  void finalizesBudgetWhenIntermediateStageFails() throws Exception {
    Fixture fixture = new Fixture(true, false, false);
    AiCostControl aiCostControl = mock(AiCostControl.class);
    fixture.consumer.setAiCostControl(aiCostControl);
    fixture.deliverSingleMessage("1", Map.of(
        AsyncTaskStreamConstants.FIELD_OWNER_ID, "7",
        AsyncTaskStreamConstants.FIELD_AI_FEATURE, "PAPER_IMPORT",
        AsyncTaskStreamConstants.FIELD_AI_OPERATION_ID, "op-1"
    ));

    fixture.consumer.init();

    verify(aiCostControl, timeout(2000)).finalize("op-1");
    fixture.close();
  }

  private static final class Fixture {

    private final RedisService redis = mock(RedisService.class);
    private final AtomicBoolean delivered = new AtomicBoolean();
    private final TestConsumer consumer;
    private final StreamWorkerRegistry registry;

    private Fixture(boolean failOneMessage) {
      this(failOneMessage, false);
    }

    private Fixture(boolean failOneMessage, boolean retryableFailure) {
      this(failOneMessage, retryableFailure, true);
    }

    private Fixture(
        boolean failOneMessage,
        boolean retryableFailure,
        boolean finalizeBudgetOnSuccess
    ) {
      ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
      when(meterProvider.getIfAvailable()).thenReturn(null);
      StreamConsumerProperties properties = new StreamConsumerProperties();
      properties.getDefaults().setWorkerThreads(2);
      properties.getDefaults().setQueueCapacity(2);
      properties.getDefaults().setDrainTimeout(Duration.ofSeconds(2));
      properties.getDefaults().setPropagateSecurityContext(false);
      registry = new StreamWorkerRegistry(
          new BoundedExecutorFactory(meterProvider),
          properties,
          redis,
          meterProvider
      );
      consumer = new TestConsumer(
          redis,
          registry,
          failOneMessage,
          retryableFailure,
          finalizeBudgetOnSuccess
      );
    }

    private void deliverTwoMessages() {
      doAnswer(invocation -> {
        if (delivered.compareAndSet(false, true)) {
          RedisService.StreamMessageProcessor processor = invocation.getArgument(7);
          processor.process(new StreamMessageId(1, 0), Map.of("item", "1"));
          processor.process(new StreamMessageId(2, 0), Map.of("item", "2"));
        } else {
          Thread.sleep(20);
        }
        return true;
      }).when(redis).streamConsumeMessages(
          anyString(), anyString(), anyString(), anyInt(), anyLong(), anyLong(), anyInt(), any());
    }

    private void deliverSingleMessage(String value) {
      deliverSingleMessage(value, Map.of());
    }

    private void deliverSingleMessage(String value, Map<String, String> extraFields) {
      doAnswer(invocation -> {
        if (delivered.compareAndSet(false, true)) {
          RedisService.StreamMessageProcessor processor = invocation.getArgument(7);
          java.util.Map<String, String> data = new java.util.HashMap<>();
          data.put("item", value);
          data.putAll(extraFields);
          processor.process(new StreamMessageId(1, 0), data);
        } else {
          Thread.sleep(20);
        }
        return true;
      }).when(redis).streamConsumeMessages(
          anyString(), anyString(), anyString(), anyInt(), anyLong(), anyLong(), anyInt(), any());
    }

    private void close() {
      consumer.shutdown();
      registry.close();
    }
  }

  private static final class TestConsumer extends AbstractStreamConsumer<String> {

    private final boolean failOneMessage;
    private final boolean retryableFailure;
    private final boolean finalizeBudgetOnSuccess;
    private final CountDownLatch bothStarted = new CountDownLatch(2);
    private final CountDownLatch terminalStateAttempted = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicInteger failureCount =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger retryCount =
        new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicReference<CountDownLatch> release = new AtomicReference<>();
    private volatile boolean failTerminalState;

    private TestConsumer(
        RedisService redisService,
        StreamWorkerRegistry workerRegistry,
        boolean failOneMessage,
        boolean retryableFailure,
        boolean finalizeBudgetOnSuccess
    ) {
      super(redisService, workerRegistry);
      this.failOneMessage = failOneMessage;
      this.retryableFailure = retryableFailure;
      this.finalizeBudgetOnSuccess = finalizeBudgetOnSuccess;
    }

    private void blockUntilReleased(CountDownLatch release) {
      this.release.set(release);
    }

    @Override
    protected String taskDisplayName() {
      return "测试任务";
    }

    @Override
    protected String streamKey() {
      return "test:stream";
    }

    @Override
    protected String groupName() {
      return "test-group";
    }

    @Override
    protected String consumerPrefix() {
      return "test-";
    }

    @Override
    protected String consumerKey() {
      return "test-consumer";
    }

    @Override
    protected String threadName() {
      return "test-stream-dispatcher";
    }

    @Override
    protected String parsePayload(StreamMessageId messageId, Map<String, String> data) {
      return data.get("item");
    }

    @Override
    protected String payloadIdentifier(String payload) {
      return "item=" + payload;
    }

    @Override
    protected void markProcessing(String payload) {
    }

    @Override
    protected void processBusiness(String payload) {
      bothStarted.countDown();
      CountDownLatch gate = release.get();
      if (gate != null) {
        try {
          gate.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      if (failOneMessage && "1".equals(payload)) {
        throw new BusinessException(
            retryableFailure ? ErrorCode.AI_SERVICE_TIMEOUT : ErrorCode.INTERNAL_ERROR,
            "模拟失败");
      }
    }

    @Override
    protected void markCompleted(String payload) {
      if (failTerminalState) {
        terminalStateAttempted.countDown();
        throw new IllegalStateException("模拟终态写失败");
      }
    }

    @Override
    protected void markFailed(String payload, String error) {
      failureCount.incrementAndGet();
    }

    @Override
    protected boolean shouldFinalizeBudgetOnSuccess() {
      return finalizeBudgetOnSuccess;
    }

    @Override
    protected void retryMessage(String payload, int retryCount) {
      this.retryCount.incrementAndGet();
      if (!retryableFailure) {
        throw new IllegalStateException("确定性失败不应重投");
      }
    }
  }
}
