package interview.guide.modules.aicost.service;

import jakarta.validation.Valid;
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
 * 单个 Provider 的并发上限。
 *
 * <p>免费模型由 {@link FreeModelConcurrencyGate} 按零价格判定；这里用于给付费
 * Provider 也加一道按实际 Provider + 模态隔离的并发保护，避免同一厂商的多个请求
 * 同时打到上游后触发 429。</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.provider-concurrency")
@Getter
@Setter
public class ProviderConcurrencyProperties {

  private boolean enabled = true;

  @Valid
  private Map<String, ProviderLimits> providers = new HashMap<>();

  @Getter
  @Setter
  public static class ProviderLimits {

    @Min(1)
    @Max(32)
    private Integer textPermits;

    @Min(1)
    @Max(32)
    private Integer visionPermits;

    @Min(1)
    @Max(32)
    private Integer embeddingPermits;

    @Min(1)
    @Max(32)
    private Integer audioPermits;
  }
}
