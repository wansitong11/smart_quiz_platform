package interview.guide.modules.aicost.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiCallDailyAggregator {

  private final JdbcTemplate jdbcTemplate;

  @Scheduled(cron = "0 30 1 * * ?")
  public void backfillYesterday() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate yesterday = today.minusDays(1);
    Instant from = yesterday.atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant to = today.atStartOfDay().toInstant(ZoneOffset.UTC);
    try {
      int updated = jdbcTemplate.update("""
          INSERT INTO ai_call_daily_agg
            (agg_date, tenant_id, user_id, feature, provider, model, call_count,
             success_count, input_tokens, output_tokens, operation_count, estimated_cost)
          SELECT occurred_at::date, 'default', COALESCE(user_id, 0), feature, provider, model,
                 count(*),
                 COALESCE(sum(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0),
                 COALESCE(sum(input_tokens), 0),
                 COALESCE(sum(output_tokens), 0),
                 count(DISTINCT operation_id),
                 COALESCE(sum(estimated_cost), 0)
          FROM ai_call_events
          WHERE occurred_at >= ? AND occurred_at < ?
          GROUP BY 1, 2, 3, 4, 5, 6
          ON CONFLICT (agg_date, tenant_id, user_id, feature, provider, model) DO UPDATE SET
            call_count = EXCLUDED.call_count,
            success_count = EXCLUDED.success_count,
            input_tokens = EXCLUDED.input_tokens,
            output_tokens = EXCLUDED.output_tokens,
            operation_count = EXCLUDED.operation_count,
            estimated_cost = EXCLUDED.estimated_cost
          """, Timestamp.from(from), Timestamp.from(to));
      log.info("[AiCallDailyAggregator] 已回填 {} 天聚合", updated);
    } catch (Exception e) {
      log.warn("[AiCallDailyAggregator] 日聚合回填失败", e);
    }
  }
}
