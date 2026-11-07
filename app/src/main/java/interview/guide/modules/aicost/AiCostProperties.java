package interview.guide.modules.aicost;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.cost")
public class AiCostProperties {

  private boolean enabled = true;
  private Ledger ledger = new Ledger();
  private Budget budget = new Budget();
  private Estimator estimator = new Estimator();
  private Prices prices = new Prices();
  private Quota quota = new Quota();

  @Data
  public static class Ledger {
    private boolean async = true;
    private int retentionDays = 90;
    private int queueCapacity = 10000;
    private int flushBatchSize = 200;
    private String cleanupCron = "0 10 3 * * ?";
  }

  @Data
  public static class Budget {
    private Default defaultRules = new Default();
    private Map<String, FeatureBudget> features = new HashMap<>();
    private String reconciliationCron = "0 */15 * * * ?";
    private int staleReservedHours = 2;
  }

  @Data
  public static class Default {
    private int rpm = 10;
    private long tpm = 200000;
    private int concurrency = 4;
  }

  @Data
  public static class FeatureBudget {
    private Integer rpm;
    private Long tpm;
    private Integer concurrency;
    private boolean reserve;
  }

  @Data
  public static class Estimator {
    private BigDecimal defaultOperationCny = new BigDecimal("0.01");
    private long paperTokensPerPage = 2000;
    private long quizTokensPerQuestion = 1200;
    private long qaContextTokens = 4000;
    private long qaOutputTokens = 800;
    private long vectorizeTokensPerChunk = 800;
  }

  @Data
  public static class Prices {
    private String syncUrl;
    private String localPath;
    private String syncCron = "0 20 4 * * ?";
    private String syncZone = "Asia/Shanghai";
    private boolean seedOnStartup = true;
  }

  @Data
  public static class Quota {
    /** 月度总量池兜底值（元）；实际以 ai_quota_config 表为准。 */
    private BigDecimal monthlyTotalCny = new BigDecimal("100");
  }
}
