package interview.guide.modules.aicost.service;

import interview.guide.modules.aicost.dto.AiUsageDtos.ErrorItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.EventItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.ModelOption;
import interview.guide.modules.aicost.dto.AiUsageDtos.PageResult;
import interview.guide.modules.aicost.dto.AiUsageDtos.RankingItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.ReportSummary;
import interview.guide.modules.aicost.dto.AiUsageDtos.TrendPoint;
import interview.guide.modules.aicost.dto.AiUsageDtos.UserUsageItem;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiCostReportService {

  private final JdbcTemplate jdbcTemplate;

  public ReportSummary summary(ReportFilter filter) {
    Map<String, Object> row = jdbcTemplate.queryForMap("""
        SELECT count(*) AS total_calls,
               COALESCE(sum(estimated_cost), 0) AS cost,
               CASE WHEN count(*) > 0
                 THEN 100.0 * sum(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) / count(*)
                 ELSE 100.0 END AS success_rate,
               COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95,
               COALESCE(sum(input_tokens), 0) AS input_tokens,
               COALESCE(sum(output_tokens), 0) AS output_tokens,
               COALESCE(sum(CASE WHEN status = 'BUDGET_EXCEEDED' THEN 1 ELSE 0 END), 0) AS budget_exceeded
        FROM ai_call_events
        WHERE %s
        """.formatted(filter.where(null)), filter.args().toArray());
    BigDecimal successRate = decimal(row.get("success_rate"));
    return new ReportSummary(
        longVal(row.get("total_calls")),
        decimal(row.get("cost")),
        BigDecimal.valueOf(100).subtract(successRate).max(BigDecimal.ZERO),
        decimal(row.get("p95")),
        longVal(row.get("input_tokens")),
        longVal(row.get("output_tokens")),
        longVal(row.get("budget_exceeded"))
    );
  }

  public List<TrendPoint> trend(ReportFilter filter) {
    Map<Instant, TrendPoint> byDay = new HashMap<>();
    jdbcTemplate.query("""
        SELECT date_trunc('day', occurred_at AT TIME ZONE 'UTC') AS d,
               count(*) AS calls,
               COALESCE(sum(estimated_cost), 0) AS cost,
               CASE WHEN count(*) > 0
                 THEN 100.0 * sum(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) / count(*)
                 ELSE 100.0 END AS success_rate
        FROM ai_call_events
        WHERE %s
        GROUP BY d ORDER BY d
        """.formatted(filter.where(null)), rs -> {
          LocalDate date = rs.getDate("d").toLocalDate();
          Instant day = date.atStartOfDay(ZoneOffset.UTC).toInstant();
          byDay.put(day, new TrendPoint(
              day,
              rs.getLong("calls"),
              rs.getBigDecimal("cost"),
              BigDecimal.valueOf(100)
                  .subtract(rs.getBigDecimal("success_rate"))
                  .max(BigDecimal.ZERO)
          ));
        }, filter.args().toArray());

    return fillTrend(filter, byDay);
  }

  static List<TrendPoint> fillTrend(
      ReportFilter filter, Map<Instant, TrendPoint> byDay) {
    List<TrendPoint> points = new ArrayList<>();
    Instant day = filter.from().atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant();
    while (day.isBefore(filter.to())) {
      TrendPoint existing = byDay.get(day);
      points.add(existing == null
          ? new TrendPoint(day, 0, BigDecimal.ZERO, BigDecimal.ZERO)
          : existing);
      day = day.plus(1, ChronoUnit.DAYS);
    }
    return points;
  }

  public List<RankingItem> ranking(ReportFilter filter, String dimension) {
    String column = switch (dimension == null ? "" : dimension.toUpperCase()) {
      case "PROVIDER" -> "provider";
      case "MODEL" -> "model";
      case "USER" -> "user_id::text";
      default -> "feature";
    };
    String sql = """
        SELECT %s AS key,
               count(*) AS calls,
               COALESCE(sum(estimated_cost), 0) AS cost,
               COALESCE(sum(input_tokens), 0) AS input_tokens,
               COALESCE(sum(output_tokens), 0) AS output_tokens
        FROM ai_call_events
        WHERE %s
        GROUP BY key ORDER BY cost DESC LIMIT 20
        """.formatted(column, filter.where(null));
    return jdbcTemplate.query(sql, (rs, i) -> new RankingItem(
        rs.getString("key"),
        rs.getLong("calls"),
        rs.getBigDecimal("cost"),
        rs.getLong("input_tokens"),
        rs.getLong("output_tokens")
    ), filter.args().toArray());
  }

  public PageResult<UserUsageItem> users(ReportFilter filter, int page, int size) {
    List<Object> args = filter.args();
    Long total = jdbcTemplate.queryForObject("""
        SELECT count(DISTINCT e.user_id) FROM ai_call_events e
        WHERE e.user_id IS NOT NULL AND %s
        """.formatted(filter.where("e")), Long.class, args.toArray());
    int limit = Math.max(1, size);
    int offset = Math.max(0, page) * limit;
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);
    List<UserUsageItem> items = jdbcTemplate.query("""
        SELECT e.user_id,
               COALESCE(NULLIF(u.display_name, ''), u.username, '未知用户') AS user_name,
               count(*) AS calls,
               COALESCE(sum(e.input_tokens), 0) AS input_tokens,
               COALESCE(sum(e.output_tokens), 0) AS output_tokens,
               COALESCE(sum(e.estimated_cost), 0) AS cost,
               CASE WHEN count(*) > 0
                 THEN 100.0 * sum(CASE WHEN e.status = 'SUCCESS' THEN 1 ELSE 0 END) / count(*)
                 ELSE 100.0 END AS success_rate
        FROM ai_call_events e
        LEFT JOIN users u ON u.id = e.user_id
        WHERE e.user_id IS NOT NULL AND %s
        GROUP BY e.user_id, u.display_name, u.username
        ORDER BY cost DESC LIMIT ? OFFSET ?
        """.formatted(filter.where("e")), (rs, i) -> new UserUsageItem(
            rs.getLong("user_id"),
            rs.getString("user_name"),
            rs.getLong("calls"),
            rs.getLong("input_tokens"),
            rs.getLong("output_tokens"),
            rs.getBigDecimal("cost"),
            rs.getBigDecimal("success_rate")
    ), pageArgs.toArray());
    return new PageResult<>(items, total == null ? 0 : total);
  }

  public List<ErrorItem> errors(ReportFilter filter) {
    return jdbcTemplate.query("""
        SELECT COALESCE(error_class, status) AS key, count(*) AS count
        FROM ai_call_events
        WHERE status <> 'SUCCESS' AND %s
        GROUP BY key ORDER BY count DESC LIMIT 20
        """.formatted(filter.where(null)),
        (rs, i) -> new ErrorItem(rs.getString("key"), rs.getLong("count")),
        filter.args().toArray());
  }

  public PageResult<EventItem> events(
      ReportFilter filter, String feature, String status, Long userId, int page, int size
  ) {
    StringBuilder where = new StringBuilder(filter.where("e"));
    List<Object> args = filter.args();
    if (feature != null && !feature.isBlank()) {
      where.append(" AND e.feature = ?");
      args.add(feature);
    }
    if (status != null && !status.isBlank()) {
      where.append(" AND e.status = ?");
      args.add(status);
    }
    if (userId != null) {
      where.append(" AND e.user_id = ?");
      args.add(userId);
    }
    Long total = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM ai_call_events e WHERE " + where,
        Long.class,
        args.toArray());
    int limit = Math.max(1, size);
    int offset = Math.max(0, page) * limit;
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);
    List<EventItem> items = jdbcTemplate.query("""
        SELECT e.id, e.occurred_at, e.user_id,
               COALESCE(NULLIF(u.display_name, ''), u.username, '未知用户') AS user_name,
               e.feature, e.provider, e.model, e.modality, e.status,
               e.latency_ms, e.input_tokens, e.output_tokens, e.estimated_cost
        FROM ai_call_events e
        LEFT JOIN users u ON u.id = e.user_id
        WHERE %s
        ORDER BY e.occurred_at DESC LIMIT ? OFFSET ?
        """.formatted(where), (rs, i) -> new EventItem(
            rs.getLong("id"),
            rs.getTimestamp("occurred_at").toInstant(),
            (Long) rs.getObject("user_id"),
            rs.getString("user_name"),
            rs.getString("feature"),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getString("modality"),
            rs.getString("status"),
            (Long) rs.getObject("latency_ms"),
            (Long) rs.getObject("input_tokens"),
            (Long) rs.getObject("output_tokens"),
            rs.getBigDecimal("estimated_cost")
    ), pageArgs.toArray());
    return new PageResult<>(items, total == null ? 0 : total);
  }

  public List<ModelOption> models(Instant from, Instant to) {
    return jdbcTemplate.query("""
        SELECT provider, model, modality
        FROM ai_call_events
        WHERE occurred_at >= ? AND occurred_at < ?
          AND feature <> 'VOICE_EVALUATE' AND modality <> 'AUDIO'
        GROUP BY provider, model, modality
        ORDER BY provider, model, modality
        """, (rs, i) -> new ModelOption(
            rs.getString("provider"),
            rs.getString("model"),
            rs.getString("modality")
        ), timestamp(from), timestamp(to));
  }

  private static BigDecimal decimal(Object value) {
    return value instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(value));
  }

  private static long longVal(Object value) {
    return value instanceof Number n ? n.longValue() : 0;
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
