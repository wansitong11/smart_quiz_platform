package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.aicost.dto.AiUsageDtos.TrendPoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiCostReportServiceTrendTest {

  @Test
  @DisplayName("趋势按 UTC 日期补零并忽略超出结束时刻的日期")
  void fillMissingDays() {
    ReportFilter filter = new ReportFilter(
        Instant.parse("2026-11-01T10:00:00Z"),
        Instant.parse("2026-11-04T00:00:00Z"),
        null, null, null);
    Instant secondDay = Instant.parse("2026-11-02T00:00:00Z");
    TrendPoint existing = new TrendPoint(
        secondDay, 3, new BigDecimal("0.12"), new BigDecimal("0.5"));

    List<TrendPoint> points = AiCostReportService.fillTrend(
        filter, Map.of(secondDay, existing));

    assertThat(points).hasSize(3);
    assertThat(points.get(0).date()).isEqualTo("2026-11-01T00:00:00Z");
    assertThat(points.get(0).calls()).isZero();
    assertThat(points.get(0).cost()).isEqualByComparingTo("0");
    assertThat(points.get(1)).isEqualTo(existing);
    assertThat(points.get(2).date()).isEqualTo("2026-11-03T00:00:00Z");
    assertThat(points)
        .noneMatch(point -> point.date().equals(Instant.parse("2026-11-04T00:00:00Z")));
  }
}
