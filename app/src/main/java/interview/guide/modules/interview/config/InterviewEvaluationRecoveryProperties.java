package interview.guide.modules.interview.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 批改任务卡死恢复配置。
 */
@Component
@ConfigurationProperties(prefix = "app.async.recovery.interview-evaluate")
@Getter
@Setter
public class InterviewEvaluationRecoveryProperties {

  private boolean enabled = true;
  private Duration processingThreshold = Duration.ofMinutes(15);
  private int batchSize = 20;
}
