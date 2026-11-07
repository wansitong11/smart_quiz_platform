package interview.guide.common.ai;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 单 vision provider 的准入、等待队列和熔断配置。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.vision-guard")
public class VisionRequestGuardProperties {

  @Min(1)
  private int maxInFlight = 4;

  @Min(0)
  private int maxQueue = 10;

  @NotNull
  private Duration acquireTimeout = Duration.ofSeconds(30);

  @Min(1)
  private int circuitFailureThreshold = 5;

  @NotNull
  private Duration circuitOpenDuration = Duration.ofSeconds(30);

  @Min(1)
  private int circuitHalfOpenProbes = 1;
}
