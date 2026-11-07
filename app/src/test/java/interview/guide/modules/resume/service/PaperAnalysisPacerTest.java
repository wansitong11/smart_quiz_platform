package interview.guide.modules.resume.service;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.aicost.service.AiUsageGate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("解析批次 RPM 节流")
class PaperAnalysisPacerTest {

  private static final Long USER_ID = 7L;
  private static final Long PAPER_ID = 53L;

  @Mock
  private AiUsageGate usageGate;

  private final PaperAnswerExecutorProperties properties =
      new PaperAnswerExecutorProperties();

  @AfterEach
  void tearDown() {
    AiCallContextHolder.clear();
    Thread.interrupted();
  }

  @Test
  @DisplayName("RPM 未满时不等待")
  void shouldNotWaitWhenRpmAvailable() {
    context();
    when(usageGate.remainingRpmWindowMillisIfExhausted(
        AiFeature.PAPER_EXTRACTION, USER_ID)).thenReturn(0L);

    new PaperAnalysisPacer(usageGate, properties).awaitAvailable(PAPER_ID);

    verify(usageGate).remainingRpmWindowMillisIfExhausted(
        AiFeature.PAPER_EXTRACTION, USER_ID);
  }

  @Test
  @DisplayName("RPM 已满时等待时间不超过配置上限")
  void shouldCapWaitAtConfiguredMaximum() {
    context();
    properties.setAnalysisRateLimitMaxWait(Duration.ofMillis(20));
    when(usageGate.remainingRpmWindowMillisIfExhausted(
        AiFeature.PAPER_EXTRACTION, USER_ID)).thenReturn(5_000L);

    long start = System.nanoTime();
    new PaperAnalysisPacer(usageGate, properties).awaitAvailable(PAPER_ID);
    long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

    assertThat(elapsedMs).isBetween(10L, 1_000L);
  }

  @Test
  @DisplayName("等待被中断时返回业务失败并保留中断标记")
  void shouldFailWhenWaitInterrupted() {
    context();
    properties.setAnalysisRateLimitMaxWait(Duration.ofMillis(20));
    when(usageGate.remainingRpmWindowMillisIfExhausted(
        AiFeature.PAPER_EXTRACTION, USER_ID)).thenReturn(5_000L);
    Thread.currentThread().interrupt();

    assertThatThrownBy(() ->
        new PaperAnalysisPacer(usageGate, properties).awaitAvailable(PAPER_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("中断");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  @DisplayName("免费模型解析批次前执行冷却")
  void shouldWaitFreeModelCooldown() {
    context();
    properties.setAnalysisFreeModelCooldown(Duration.ofMillis(123));
    when(usageGate.remainingRpmWindowMillisIfExhausted(
        AiFeature.PAPER_EXTRACTION, USER_ID)).thenReturn(0L);
    AtomicLong sleptMillis = new AtomicLong();

    new PaperAnalysisPacer(usageGate, properties) {
      @Override
      protected void sleep(long millis) {
        sleptMillis.set(millis);
      }
    }.awaitAvailable(PAPER_ID, true);

    assertThat(sleptMillis).hasValue(123L);
  }

  @Test
  @DisplayName("付费模型不执行免费模型冷却")
  void shouldNotWaitCooldownForPaidModel() {
    context();
    properties.setAnalysisFreeModelCooldown(Duration.ofMillis(123));
    when(usageGate.remainingRpmWindowMillisIfExhausted(
        AiFeature.PAPER_EXTRACTION, USER_ID)).thenReturn(0L);
    AtomicInteger sleeps = new AtomicInteger();

    new PaperAnalysisPacer(usageGate, properties) {
      @Override
      protected void sleep(long millis) {
        sleeps.incrementAndGet();
      }
    }.awaitAvailable(PAPER_ID, false);

    assertThat(sleeps).hasValue(0);
  }

  private void context() {
    AiCallContextHolder.set(new AiCallContextHolder.AiCallContext(
        USER_ID, AiFeature.PAPER_EXTRACTION, "paper-analysis", null, null, "TEST"));
  }
}
