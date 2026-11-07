package interview.guide.modules.aicost.service;

import interview.guide.modules.aicost.AiCostProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按保留天数清理明细账本，控制 ai_call_events 表体积。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiLedgerRetentionJob {

  private final JdbcTemplate jdbcTemplate;
  private final AiCostProperties properties;

  @Scheduled(cron = "${app.ai.cost.ledger.cleanup-cron:0 10 3 * * ?}",
      zone = "${app.ai.cost.prices.sync-zone:Asia/Shanghai}")
  public void cleanup() {
    Instant cutoff = Instant.now()
        .minus(Duration.ofDays(properties.getLedger().getRetentionDays()));
    try {
      int deleted = jdbcTemplate.update(
          "DELETE FROM ai_call_events WHERE occurred_at < ?", Timestamp.from(cutoff));
      log.info("[AiLedgerRetentionJob] 已清理 {} 条过期明细", deleted);
    } catch (Exception e) {
      log.warn("[AiLedgerRetentionJob] 账本保留清理失败", e);
    }
  }
}
