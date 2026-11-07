package interview.guide.common.ai;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("模型限流共享冷却")
class AiRateLimitThrottleTest {

  private AiRateLimitThrottleProperties properties;

  @BeforeEach
  void setUp() {
    properties = new AiRateLimitThrottleProperties();
    properties.setFallbackBaseMs(100);
    properties.setFallbackJitterMs(20);
    properties.setMaxWaitMs(1_000);
  }

  @Test
  @DisplayName("带 Retry-After 的 429 会等待，冷却结束后不再等待")
  void shouldWaitForRetryAfterUntilCooldownExpires() {
    RecordingThrottle throttle = new RecordingThrottle(properties);
    RateLimitException error = RateLimitException.builder()
        .headers(Headers.builder().put("retry-after", "0.02").build())
        .build();

    throttle.noteFailure("provider", "model", "TEXT", error);
    throttle.awaitOpen("provider", "model", "TEXT");
    throttle.awaitOpen("provider", "model", "TEXT");

    assertThat(throttle.waits()).hasSize(1);
    assertThat(throttle.waits().getFirst()).isBetween(1L, 1_000L);
  }

  @Test
  @DisplayName("没有 Retry-After 时使用带抖动的兜底等待")
  void shouldUseFallbackDelayWhenRetryAfterIsMissing() {
    RecordingThrottle throttle = new RecordingThrottle(properties);

    throttle.noteFailure("provider", "model", "TEXT", new RuntimeException("429"));
    throttle.awaitOpen("provider", "model", "TEXT");

    assertThat(throttle.waits()).hasSize(1);
    assertThat(throttle.waits().getFirst()).isBetween(100L, 120L);
  }

  @Test
  @DisplayName("非限流异常不会触发共享冷却")
  void shouldIgnoreNonRateLimitFailure() {
    RecordingThrottle throttle = new RecordingThrottle(properties);

    throttle.noteFailure("provider", "model", "TEXT",
        new IllegalArgumentException("bad request"));
    throttle.awaitOpen("provider", "model", "TEXT");

    assertThat(throttle.waits()).isEmpty();
  }

  @Test
  @DisplayName("禁用共享冷却时既不记录也不等待")
  void shouldBypassWhenDisabled() {
    properties.setEnabled(false);
    RecordingThrottle throttle = new RecordingThrottle(properties);

    throttle.noteFailure("provider", "model", "TEXT", new RuntimeException("429"));
    throttle.awaitOpen("provider", "model", "TEXT");

    assertThat(throttle.waits()).isEmpty();
  }

  @Test
  @DisplayName("不同模型的冷却互不影响")
  void shouldKeepCooldownPerModel() {
    RecordingThrottle throttle = new RecordingThrottle(properties);

    throttle.noteFailure("provider", "model-a", "TEXT", new RuntimeException("429"));
    throttle.awaitOpen("provider", "model-b", "TEXT");

    assertThat(throttle.waits()).isEmpty();
  }

  private static final class RecordingThrottle extends AiRateLimitThrottle {

    private final List<Long> waits = new ArrayList<>();

    private RecordingThrottle(AiRateLimitThrottleProperties properties) {
      super(properties);
    }

    @Override
    protected void sleep(long millis) throws InterruptedException {
      waits.add(millis);
      Thread.sleep(Math.max(millis, 30L));
    }

    private List<Long> waits() {
      return waits;
    }
  }
}
