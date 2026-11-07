package interview.guide.modules.aicost.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 免费模型调用的全局并发上限。
 *
 * <p>多个用户同时使用免费模型时共享同一组许可；付费模型不经过这里的等待。</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.free-model-concurrency")
@Getter
@Setter
public class FreeModelConcurrencyProperties {

  private boolean enabled = true;

  @Min(1)
  @Max(32)
  private int textPermits = 1;

  @Min(1)
  @Max(32)
  private int visionPermits = 1;

  @Min(1)
  @Max(32)
  private int embeddingPermits = 1;

  @Min(1)
  @Max(32)
  private int audioPermits = 1;

  @Min(1)
  @Max(10)
  private int analysisBatchSize = 4;

  /**
   * 单个免费 Provider 的并发覆盖。
   *
   * <p>未配置的 Provider 仍使用上面的模态默认值。这样同一个全局闸门可以同时容纳
   * “8 并发的免费文本模型”和“只能 1 并发的免费视觉模型”，不会为了一个模型把另一个模型也串行化。</p>
   */
  private Map<String, ProviderPermits> providerPermits = new HashMap<>();

  @Getter
  @Setter
  public static class ProviderPermits {
    private Integer textPermits;
    private Integer visionPermits;
    private Integer embeddingPermits;
    private Integer audioPermits;
  }
}
