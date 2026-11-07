package interview.guide.modules.aicost.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端调用报表的统一筛选条件。
 */
public record ReportFilter(
    Instant from,
    Instant to,
    String provider,
    String model,
    String modality
) {

  public boolean hasModel() {
    return !isBlank(provider) || !isBlank(model) || !isBlank(modality);
  }

  /**
   * 生成时间与模型过滤条件；需要连表时传入表别名。
   */
  public String where(String alias) {
    String prefix = isBlank(alias) ? "" : alias + ".";
    StringBuilder where = new StringBuilder()
        .append(prefix).append("occurred_at >= ? AND ")
        .append(prefix).append("occurred_at < ? AND ")
        .append(prefix).append("feature <> 'VOICE_EVALUATE' AND ")
        .append(prefix).append("modality <> 'AUDIO'");
    if (!isBlank(provider)) {
      where.append(" AND ").append(prefix).append("provider = ?");
    }
    if (!isBlank(model)) {
      where.append(" AND ").append(prefix).append("model = ?");
    }
    if (!isBlank(modality)) {
      where.append(" AND ").append(prefix).append("modality = ?");
    }
    return where.toString();
  }

  public List<Object> args() {
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(from));
    args.add(Timestamp.from(to));
    if (!isBlank(provider)) {
      args.add(provider);
    }
    if (!isBlank(model)) {
      args.add(model);
    }
    if (!isBlank(modality)) {
      args.add(modality);
    }
    return args;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
