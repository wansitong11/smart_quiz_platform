package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportFilterTest {

  @Test
  @DisplayName("未选择模型时只过滤时间和隐藏项")
  void buildWhereWithoutModel() {
    ReportFilter filter = new ReportFilter(
        Instant.parse("2026-11-01T00:00:00Z"),
        Instant.parse("2026-11-02T00:00:00Z"),
        null, null, null);

    assertThat(filter.hasModel()).isFalse();
    assertThat(filter.where("e"))
        .contains("e.occurred_at >= ?", "e.feature <> 'VOICE_EVALUATE'", "e.modality <> 'AUDIO'")
        .doesNotContain("e.provider = ?");
    assertThat(filter.args()).hasSize(2);
  }

  @Test
  @DisplayName("选择模型时按 provider + model + modality 三元组过滤")
  void buildWhereWithModel() {
    ReportFilter filter = new ReportFilter(
        Instant.parse("2026-11-01T00:00:00Z"),
        Instant.parse("2026-11-02T00:00:00Z"),
        "dashscope", "qwen3.8-flash", "VISION");

    assertThat(filter.hasModel()).isTrue();
    assertThat(filter.where(null))
        .contains("provider = ?", "model = ?", "modality = ?");
    assertThat(filter.args())
        .hasSize(5)
        .endsWith("dashscope", "qwen3.8-flash", "VISION");
  }

  @Test
  @DisplayName("只传模型名时保留旧版 events 查询兼容性")
  void buildWhereWithModelNameOnly() {
    ReportFilter filter = new ReportFilter(
        Instant.parse("2026-11-01T00:00:00Z"),
        Instant.parse("2026-11-02T00:00:00Z"),
        null, "qwen3.8-flash", null);

    assertThat(filter.where(null))
        .contains("model = ?")
        .doesNotContain("provider = ?", "modality = ?");
    assertThat(filter.args())
        .hasSize(3)
        .endsWith("qwen3.8-flash");
  }
}
