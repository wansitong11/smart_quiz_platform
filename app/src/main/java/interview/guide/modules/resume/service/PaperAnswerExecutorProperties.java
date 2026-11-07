package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutorProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 答案键批次统一并行配置。
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.paper.answer")
@Getter
@Setter
public class PaperAnswerExecutorProperties extends BoundedExecutorProperties {

  /**
   * 每批解析的题目数。批太小会增加 RPM 压力，批太大则增加长 JSON 截断风险。
   */
  @Min(1)
  @Max(10)
  private int analysisBatchSize = 3;

  /**
   * 检测到当前分钟 RPM 已满时，最多等待多久再继续；默认覆盖完整窗口。
   */
  private Duration analysisRateLimitMaxWait = Duration.ofSeconds(65);

  /**
   * 免费文本模型每批解析前的短冷却，避免连续批次触发上游 429。
   *
   * <p>默认关闭。免费模型改由 Provider 级并发许可和共享 429 冷却控制，
   * 固定冷却只会给每批额外增加等待时间。</p>
   */
  private Duration analysisFreeModelCooldown = Duration.ZERO;

  /**
   * 缺失解析补跑的最大模型调用次数。
   */
  @Min(0)
  @Max(20)
  private int analysisRecoveryMaxCalls = 4;

  /**
   * 缺失解析补跑的总等待预算；每次提交前检查，不打断在途调用。
   */
  private Duration analysisRecoveryMaxWait = Duration.ofSeconds(60);
}
