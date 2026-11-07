package interview.guide.common.ai;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 免费模型或 Provider 级限流配置命中时的上游限流共享冷却参数。
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.rate-limit-throttle")
@Getter
@Setter
public class AiRateLimitThrottleProperties {

  private boolean enabled = true;

  @Min(0)
  private long fallbackBaseMs = 10_000;

  @Min(0)
  private long fallbackJitterMs = 2_000;

  @Min(0)
  private long maxWaitMs = 30_000;
}
