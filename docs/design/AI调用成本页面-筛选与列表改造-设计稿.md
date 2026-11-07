# AI 调用与成本页面：模型筛选、趋势补齐、用户与明细列表改造（设计稿）

> 范围：只改「AI 调用与成本」管理页及其数据接口，新增两个明细独立页。
> 不涉及计费、限流、价格同步逻辑本身；语音模型继续隐藏。

## 一、正文（给产品 / 给人看）

### 1. 目的

- 让管理员能按「某个模型」看所有数据，也能回到「全部模型」看总量。
- 让页面上的每块数据（总览、趋势、排行、用户、失败、最近调用）都服从同一个
  模型筛选和同一个时间范围，不再各看各的。
- 补上三个缺口：成本趋势图有数据却显示为空；用户用量看不到是谁；最近调用
  只显示英文功能名和模型名、看不到是谁调用的。
- 把「调用与成本 / 模型价格」两个入口从按钮换成下拉框，和模型、时间两个筛选
  放在同一排，便于一眼看清当前看的范围。

### 2. 页面概念图（简易）

```text
┌────────────────────────────────────────────────────────────────────────┐
│ AI 调用与成本                                              [视图 ▾]      │
│ 模型调用量、Token、费用、成功率与价格管理              [模型 ▾] [时间 ▾] │
├────────────────────────────────────────────────────────────────────────┤
│  视图下拉：调用与成本 / 模型价格                                        │
│  模型下拉：全部模型 / DashScope·qwen3.8-flash·文字 / ...（仅成本视图） │
│  时间下拉：近 7 天 / 近 30 天 / 近 90 天                                │
├────────────────────────────────────────────────────────────────────────┤
│ [总调用次数] [估算成本] [输入 Token] [P95 耗时]                          │
├────────────────────────────────────────────────────────────────────────┤
│ 成本趋势                                                               │
│   ▂▃▅▇▅▃▂▂（每个日期都有柱，哪怕 0 也有占位；空数据显示“暂无数据”）   │
├───────────────────────────────┬────────────────────────────────────────┤
│ 功能成本排行（中文名）          │ Provider 排行                          │
│  模拟测验出题  $1.2            │  DashScope  $2.0                       │
│  试卷识图      $0.8            │                                          │
├───────────────────────────────┴────────────────────────────────────────┤
│ 用户用量 Top 10                          [查看 →]                       │
│  系统管理员 | 15 | 1.2K | 3.4K | $1.20 | 98.0%                          │
├────────────────────────────────────────────────────────────────────────┤
│ 失败原因                      │ 最近调用                    [查看 →]     │
│  RATE_LIMITED  2             │ 11-03 10:20  系统管理员·模拟测验出题    │
│  PROVIDER_ERROR 1            │  DashScope/qwen3.8-flash 成功 $0.012    │
└────────────────────────────────────────────────────────────────────────┘

点击「查看 →」进入独立页：
  /admin/ai-cost/users   用户用量（分页，带模型/时间筛选）
  /admin/ai-cost/events  最近调用（分页，带模型/时间筛选）
```

### 3. 顶部筛选：视图、模型、时间

- 触发：管理员打开页面、切换下拉框。
- 规则与步骤：
  1. 「视图」下拉只有两项：调用与成本、模型价格。选模型价格时不显示模型筛选，
     因为价格表管理所有模型。
  2. 「模型」下拉第一项固定为「全部模型」；其余项来自当前时间范围内实际产生过
     调用的模型，按 provider + model + 模态去重。一个文字模型和一个识图模型即使
     模型名相同，也拆成两条（例如 DashScope 的文字 qwen 和识图 qwen）。
  3. 选中某个模型后，页面所有模块都只统计这个模型的调用；回到「全部模型」则
     恢复总量。
  4. 「时间」沿用近 7 / 30 / 90 天，作用于所有模块和两个明细独立页。
- 结果：页面所有数据共享同一组筛选条件，顶部一目了然。
- 边界与异常：当前时间范围内没有调用记录时，模型下拉只有「全部模型」；
  切换时间后模型下拉要重新取，避免出现早就没在用的陈旧模型。
- 可改点：想改时间档位（如加近 1 天），改前端时间选项列表；想改「全部模型」
  的文案，改前端常量。

### 4. 模型筛选口径

- 目的：同一个模型名可能既做文字又做识图，只用模型名筛选会把两者混在一起，
  成本也错算。
- 规则与步骤：唯一筛选键 = provider + model + 模态（modality）。三个值都匹配
  才算命中。模态中文显示为：文字 / 识图 / 向量；语音不展示。
- 结果：选择「DashScope · qwen3.8-flash · 文字」时，只看到文字调用，识图调用
  被排除。
- 可改点：想改成只按模型名聚合，去掉 provider 与模态条件即可。

### 5. 成本趋势补齐与空图

- 目的：数据只有一天或某几天没有调用时，图不能看起来是空的。
- 规则与步骤：
  1. 后端把所选时间范围内的每一天都返回，哪怕当天 0 调用 0 成本；
  2. 前端给柱子容器一个固定高度；某天成本为 0 时显示最小占位高度；
  3. 范围内完全没有任何记录时，显示「暂无数据」提示。
- 结果：趋势图连续、可读，0 成本的日子也能看见。
- 可改点：想调整“最小占位高度”，改前端柱子最小高度常量。

### 6. 功能成本显示中文名

- 目的：功能排行、最近调用里的功能名从英文枚举变成中文，管理员一眼看懂。
- 规则与步骤：后端仍返回英文功能码（保持接口稳定），前端用一份共享映射转中文。
  映射：模拟测验出题 / 试卷导入 / 试卷识图 / 试卷切题 / 知识库问答 /
  知识库向量化 / 知识库出题 / 作答批改；未知或未映射的显示“其他”。
  语音相关功能继续不显示（数据源已过滤）。
- 可改点：改中文文案，改前端映射文件即可，不用动后端。

### 7. 用户用量 Top 与用户名

- 目的：能看到每条用量是谁。
- 规则与步骤：
  1. 默认展示前 10 个用户，按成本从高到低；
  2. 用户名优先用“显示名”，没有显示名用“登录名”，都没有显示“未知用户”；
  3. 右上角「查看 →」进入独立页，独立页分页展示全量用户。
- 结果：列表出现真实用户名，Top 10 + 独立全量分页两处口径一致。
- 可改点：改 Top 条数，改前端请求 size；改用户名回退顺序，改后端合并规则。

### 8. 最近调用与用户名 / 中文功能名

- 目的：最近调用每条显示“谁、做了什么、哪个模型、成败、成本”。
- 规则与步骤：
  1. 每行显示时间、用户名、中文功能名、Provider/模型、状态、成本；
  2. 不显示英文功能码；状态“成功”显示为“成功”，其余显示状态原文；
  3. 右上角「查看 →」进入独立页，独立页分页展示全量最近调用，列更完整。
- 结果：最近调用从“英文码 + 模型”变成“人 + 中文功能 + 模型 + 结果”。
- 可改点：想调整列，改明细页表格列定义。

### 9. 两个独立明细页

- 触发：在主页面点击「查看 →」。
- 规则与步骤：
  1. 用户用量页 URL 带上 `days`、`provider`、`model`、`modality`、`page`、`size`；
  2. 最近调用页同样带上这些条件；
  3. 独立页从 URL 恢复筛选条件，支持分页与“返回成本页”。
- 结果：从主页面点进去看到的是同一筛选条件下的完整分页列表，刷新不丢条件。
- 边界与异常：URL 缺省时按「全部模型 + 近 7 天」处理；页码越界时后端返回空列表。

### 10. 统一时间与模型筛选的传播

- 规则与步骤：总览、趋势、功能排行、Provider 排行、用户用量、失败原因、最近调用
  七块数据，全部把同一组 `from / to / provider / model / modality` 传给后端；后端
  所有报表 SQL 用同一套过滤子句。模型下拉可选项也按同一时间范围取。
- 结果：整页口径一致，不会出现“总览是全部、列表是某个模型”的错位。

### 11. 边界与异常汇总

- 模型下拉为空：只显示「全部模型」。
- 范围内无调用：总览为 0，趋势显示“暂无数据”，排行 / 用户 / 失败 / 最近调用为空。
- 用户已被删除或 user_id 为 null：用户名显示“未知用户”，仍按 user_id 聚合。
- 成本为 null 的事件：按 0 处理（沿用现有 COALESCE 逻辑）。
- 后端异常：沿用页面现有错误提示条。

### 12. 可改点汇总

| 想改什么 | 改哪里 |
| --- | --- |
| 时间档位 | 前端时间下拉选项 |
| Top 条数 / 明细每页条数 | 前端 size 常量 |
| 中文功能名文案 | 前端功能名映射文件 |
| 模型筛选是否包含模态 | 后端过滤子句 + 前端拆分逻辑 |
| 用户名回退顺序 | 后端用户查询 SQL 的合并表达式 |
| 趋势最小柱高 / 空图文案 | 前端趋势图常量与空态 |

## 二、实现简述（给开发）

- 后端新增内部 `ReportFilter` 记录，承载 `from / to / provider / model / modality`，
  统一产出 `where` 子句与参数，七个报表方法共用，避免散落拼接。事件接口在统一
  过滤之上再叠加 feature / status / userId 动态条件。
- 新增 `GET /api/admin/ai-cost/models?from&to`：从 `ai_call_events` 按时间范围
  group by provider/model/modality，过滤语音后返回可选项。
- `trend` 改为：先 SQL 按天聚合，再用 Java 从 `from` 到 `to` 逐天补零（半开区间
  `[from, to)` 与 SQL 的 `< to` 对齐）。
- `users` 与 `events` 查询 `LEFT JOIN users`，用户名用
  `COALESCE(NULLIF(display_name,''), username, '未知用户')`，一次查询带出，不循环查。
- 前端 `aiCost.ts` 的所有报表方法改成接收 `{ days, provider, model, modality }`，
  新增 `models` 方法；`UserUsageItem` 加 `userName`，`EventItem` 加 `userName`、
  `modality`。新增共享功能名映射文件。主页面顶部视图改下拉，新增模型下拉；
  趋势图固定高度 + 空态；用户 Top 改 10 条并显示用户名，右上角加“查看”；
  最近调用显示用户名与中文功能名并加“查看”。新增两个明细页并注册路由。

## 三、附录（给 AI / 实现层，零猜测）

### A. 后端过滤对象

新增 `interview.guide.modules.aicost.service.ReportFilter`：

```java
package interview.guide.modules.aicost.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record ReportFilter(
    Instant from,
    Instant to,
    String provider,
    String model,
    String modality
) {
  boolean hasModel() {
    return !isBlank(provider) || !isBlank(model) || !isBlank(modality);
  }

  public String where(String alias) {
    String prefix = isBlank(alias) ? "" : alias + ".";
    StringBuilder sb = new StringBuilder()
        .append(prefix).append("occurred_at >= ? AND ")
        .append(prefix).append("occurred_at < ? AND ")
        .append(prefix).append("feature <> 'VOICE_EVALUATE' AND ")
        .append(prefix).append("modality <> 'AUDIO'");
    if (!isBlank(provider)) {
      sb.append(" AND ").append(prefix).append("provider = ?");
    }
    if (!isBlank(model)) {
      sb.append(" AND ").append(prefix).append("model = ?");
    }
    if (!isBlank(modality)) {
      sb.append(" AND ").append(prefix).append("modality = ?");
    }
    return sb.toString();
  }

  List<Object> args() {
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

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
```

规则：

- `provider`、`model`、`modality` 任意一个非空就追加对应过滤；三者都为空才是“全部模型”。
- 不连表传 `null`；连 `users` 时传表别名 `"e"`，避免 `status` 等同名列歧义。
- `from`、`to` 必须非空，由 Controller 的 `Range.of` 兜底。

### B. Controller 签名

文件 `app/src/main/java/interview/guide/modules/aicost/controller/AiCostAdminController.java`。

新增查询参数：`summary`、`trend`、`ranking`、`users`、`errors` 都加：

```java
@RequestParam(required = false) String provider,
@RequestParam(required = false) String model,
@RequestParam(required = false) String modality,
```

`events` 已有 `model`，再补 `provider`、`modality`：

```java
@RequestParam(required = false) String provider,
@RequestParam(required = false) String modality,
```

方法映射：

```java
@GetMapping("/summary")
public Result<ReportSummary> summary(from, to, provider, model, modality)
    -> reportService.summary(new ReportFilter(range.from(), range.to(), provider, model, modality))

@GetMapping("/trend")
public Result<List<TrendPoint>> trend(from, to, provider, model, modality)
    -> reportService.trend(new ReportFilter(...))

@GetMapping("/ranking")
public Result<List<RankingItem>> ranking(from, to, dimension, provider, model, modality)
    -> reportService.ranking(new ReportFilter(...), dimension)

@GetMapping("/users")
public Result<PageResult<UserUsageItem>> users(from, to, page, size, provider, model, modality)
    -> reportService.users(new ReportFilter(...), page, size)

@GetMapping("/errors")
public Result<List<ErrorItem>> errors(from, to, provider, model, modality)
    -> reportService.errors(new ReportFilter(...))

@GetMapping("/events")
public Result<PageResult<EventItem>> events(from, to, feature, model, status, userId, page, size, provider, modality)
    -> reportService.events(new ReportFilter(...), feature, status, userId, page, size)
```

`Range.of` 保持现有默认（`to` 缺省 now，`from` 缺省 `to - 7 天`）。

新增模型可选项接口：

```java
@GetMapping("/models")
public Result<List<ModelOption>> models(
    @RequestParam(required = false) Instant from,
    @RequestParam(required = false) Instant to) {
  Range range = Range.of(from, to);
  return Result.success(reportService.models(range.from(), range.to()));
}
```

### C. DTO 变更

文件 `app/src/main/java/interview/guide/modules/aicost/dto/AiUsageDtos.java`。

`UserUsageItem` 增加 `userName`：

```java
public record UserUsageItem(
    Long userId,
    String userName,
    long calls,
    long inputTokens,
    long outputTokens,
    BigDecimal cost,
    BigDecimal successRate
) {
}
```

`EventItem` 增加 `userName`、`modality`（字段顺序固定，前端按此解构）：

```java
public record EventItem(
    Long id,
    Instant occurredAt,
    Long userId,
    String userName,
    String feature,
    String provider,
    String model,
    String modality,
    String status,
    Long latencyMs,
    Long inputTokens,
    Long outputTokens,
    BigDecimal estimatedCost
) {
}
```

新增 `ModelOption`：

```java
public record ModelOption(
    String provider,
    String model,
    String modality
) {
}
```

### D. Service 方法（`AiCostReportService`）

所有方法的 `Instant from, Instant to` 参数改为 `ReportFilter filter`；
SQL 中写死的 `VISIBLE_EVENT_FILTER` 改用 `filter.where(null)` 与 `filter.args()`；
连表查询使用 `filter.where("e")`。

#### summary

```java
public ReportSummary summary(ReportFilter filter) {
  Map<String, Object> row = jdbcTemplate.queryForMap(
      "SELECT count(*) AS total_calls, ... FROM ai_call_events WHERE " + filter.where(null),
      filter.args().toArray());
  // 其余映射不变；success_rate 同现有：sum(SUCCESS)/count(*)
}
```

SQL 字段与现有 `summary` 完全一致，仅 `WHERE` 由 `filter.where(null)` 生成。

#### trend（补零）

```java
public List<TrendPoint> trend(ReportFilter filter) {
  Map<Instant, TrendPoint> byDay = jdbcTemplate.query(
      "SELECT date_trunc('day', occurred_at) AS d, count(*) AS calls, "
    + "COALESCE(sum(estimated_cost),0) AS cost, "
    + "CASE WHEN count(*)>0 THEN 100.0*sum(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END)/count(*) ELSE 100.0 END AS success_rate "
    + "FROM ai_call_events WHERE " + filter.where(null) + " GROUP BY d ORDER BY d",
      rs -> { /* 构造 TrendPoint，放入 LinkedHashMap */ },
      filter.args().toArray());
  List<TrendPoint> out = new ArrayList<>();
  Instant day = filter.from().truncatedTo(ChronoUnit.DAYS);
  while (day.isBefore(filter.to())) {
    TrendPoint p = byDay.get(day);
    out.add(p == null ? new TrendPoint(day, 0, BigDecimal.ZERO, BigDecimal.ZERO) : p);
    day = day.plus(1, ChronoUnit.DAYS);
  }
  return out;
}
```

说明：

- `Instant.truncatedTo(ChronoUnit.DAYS)` 得到当天 UTC 0 点，与
  `date_trunc('day', occurred_at)` 的键一致（数据库时区为 UTC）。
- 半开区间 `day.isBefore(filter.to())` 与 SQL 的 `occurred_at < to` 对齐；
  `from` 与 `to` 需要 Controller 保证 `from < to`。

#### ranking

```java
public List<RankingItem> ranking(ReportFilter filter, String dimension) {
  String column = switch (dimension == null ? "" : dimension.toUpperCase()) {
    case "PROVIDER" -> "provider";
    case "MODEL" -> "model";
    case "USER" -> "user_id::text";
    default -> "feature";
  };
  String sql = "SELECT %s AS key, count(*) AS calls, ... FROM ai_call_events WHERE %s "
      + "GROUP BY key ORDER BY cost DESC LIMIT 20".formatted(column, filter.where(null));
  return jdbcTemplate.query(sql, (rs, i) -> new RankingItem(...), filter.args().toArray());
}
```

#### users（带用户名，分页）

```java
public PageResult<UserUsageItem> users(ReportFilter filter, int page, int size) {
  List<Object> args = filter.args();
  Long total = jdbcTemplate.queryForObject(
      "SELECT count(DISTINCT e.user_id) FROM ai_call_events e "
          + "WHERE e.user_id IS NOT NULL AND " + filter.where("e"),
      Long.class, args.toArray());
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
```

注意：`users` 表也有 `status` 列，因此连表查询必须使用
`filter.where("e")`，动态条件也使用 `e.status` / `e.user_id`，不能使用无别名列名。

#### errors

```java
public List<ErrorItem> errors(ReportFilter filter) {
  String sql = "SELECT COALESCE(error_class, status) AS key, count(*) AS count "
      + "FROM ai_call_events WHERE status <> 'SUCCESS' AND " + filter.where(null)
      + " GROUP BY key ORDER BY count DESC LIMIT 20";
  return jdbcTemplate.query(sql, (rs, i) -> new ErrorItem(...), filter.args().toArray());
}
```

#### events（带用户名与模态，分页）

```java
public PageResult<EventItem> events(
    ReportFilter filter, String feature, String status, Long userId, int page, int size) {
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
      "SELECT count(*) FROM ai_call_events e WHERE " + where, Long.class, args.toArray());
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
```

注意：`where` 使用 `e.` 别名过滤；`users` 表也有 `status` 列，不能用无别名
`status = ?`，否则 PostgreSQL 会报列歧义。

#### models

```java
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
```

### E. 前端 API（`frontend/src/api/aiCost.ts`）

新增类型：

```ts
export interface ModelOption {
  provider: string;
  model: string;
  modality: string;
}

export interface ReportQuery {
  days?: number;
  provider?: string;
  model?: string;
  modality?: string;
}
```

`UserUsageItem` 加 `userName: string`；`EventItem` 加
`userName: string`、`modality: string`。

`summary`、`trend`、`ranking`、`users`、`errors` 改为接收 `ReportQuery`，
并始终带 `from`、`to`、`provider`、`model`、`modality`：

```ts
function isoRange(days: number) { /* 保持现有实现 */ }

function reportParams(q: ReportQuery) {
  const { from, to } = isoRange(q.days ?? 7);
  return { from, to, provider: q.provider, model: q.model, modality: q.modality };
}

summary: (q: ReportQuery = {}) =>
  request.get<ReportSummary>('/api/admin/ai-cost/summary', { params: reportParams(q) }),
trend: (q: ReportQuery = {}) =>
  request.get<TrendPoint[]>('/api/admin/ai-cost/trend', { params: reportParams(q) }),
ranking: (dimension: 'FEATURE' | 'PROVIDER' | 'MODEL' | 'USER', q: ReportQuery = {}) =>
  request.get<RankingItem[]>('/api/admin/ai-cost/ranking', { params: { ...reportParams(q), dimension } }),
users: (q: ReportQuery & { page?: number; size?: number } = {}) =>
  request.get<PageResult<UserUsageItem>>('/api/admin/ai-cost/users', { params: { ...reportParams(q), page: q.page ?? 0, size: q.size ?? 20 } }),
errors: (q: ReportQuery = {}) =>
  request.get<ErrorItem[]>('/api/admin/ai-cost/errors', { params: reportParams(q) }),
events: (q: ReportQuery & { feature?: string; status?: string; userId?: number; page?: number; size?: number } = {}) =>
  request.get<PageResult<EventItem>>('/api/admin/ai-cost/events', {
    params: { ...reportParams(q), feature: q.feature, status: q.status, userId: q.userId, page: q.page ?? 0, size: q.size ?? 20 },
  }),
models: (days = 7) => {
  const { from, to } = isoRange(days);
  return request.get<ModelOption[]>('/api/admin/ai-cost/models', { params: { from, to } });
},
```

### F. 前端共享中文名映射

新建 `frontend/src/constants/aiCostLabels.ts`：

```ts
export const AI_FEATURE_NAMES: Record<string, string> = {
  QUIZ_GENERATE: '模拟测验出题',
  PAPER_IMPORT: '试卷导入',
  PAPER_RECOGNIZE: '试卷识图',
  PAPER_EXTRACTION: '试卷切题',
  KB_QA: '知识库问答',
  KB_VECTORIZE: '知识库向量化',
  KB_QUESTION_GEN: '知识库出题',
  ANSWER_EVALUATE: '作答批改',
  UNKNOWN: '其他',
};

export const AI_MODALITY_NAMES: Record<string, string> = {
  TEXT: '文字',
  VISION: '识图',
  EMBEDDING: '向量',
};

export const AI_PROVIDER_NAMES: Record<string, string> = {
  dashscope: 'DashScope',
  'dashscope-vl': 'DashScope-VL',
};

export function featureName(feature: string): string {
  return AI_FEATURE_NAMES[feature] ?? '其他';
}

export function modalityName(modality: string): string {
  return AI_MODALITY_NAMES[modality] ?? modality;
}

export function modelLabel(m: { provider: string; model: string; modality: string }): string {
  const p = AI_PROVIDER_NAMES[m.provider] ?? m.provider;
  return `${p} / ${m.model} / ${modalityName(m.modality)}`;
}
```

### G. 主页面改造（`frontend/src/pages/admin/AiCostPage.tsx`）

状态：

```ts
type View = 'cost' | 'prices';
const [view, setView] = useState<View>('cost');
const [days, setDays] = useState(7);
const [models, setModels] = useState<ModelOption[]>([]);
const [selected, setSelected] = useState<ModelOption | null>(null); // null = 全部模型
```

请求：

- `view === 'cost'` 时，`days` 变化后拉 `aiCostApi.models(days)`；
  若当前 `selected` 不在新列表里，重置为 `null`。
- `loadCost` 依赖 `[days, selected]`，所有请求用同一个
  `{ days, provider: selected?.provider, model: selected?.model, modality: selected?.modality }`。
- 用户列表请求 `size: 10`；最近调用 `size: 10`。

顶部：

- 「视图」用 `<select>`：`调用与成本` / `模型价格`。
- 仅 `view === 'cost'` 显示「模型」`<select>`，第一项 `全部模型`（value 空），
  其余用 `modelLabel(m)` 作文案、value 用 `${provider}|${model}|${modality}`；
  `onChange` 用分隔符还原为 `ModelOption`，空值还原为 `null`。
- 「时间」`<select>` 保持 7/30/90。

趋势图修复：

- 柱容器加固定高度类，例如外层 `h-40`；柱的百分比高度相对该容器计算；
- 每根柱 `height` 用现有 `(cost/maxCost)*100`，但最小 `4%`（现有已有
  `Math.max(4, ...)`，保留即可，关键是外层必须有确定高度）；
- `trend.length === 0` 时显示“暂无数据”。

功能排行：

- `featureRank` 的 `key` 用 `featureName(i.key)` 显示，排序与值不变。

用户用量 Top：

- 表格「用户」列显示 `u.userName`（不再显示 `#u.userId`）。
- 区块右上角加「查看」链接：

```ts
const userUrl = `/admin/ai-cost/users?days=${days}`
  + (selected ? `&provider=${encodeURIComponent(selected.provider)}&model=${encodeURIComponent(selected.model)}&modality=${encodeURIComponent(selected.modality)}` : '');
```

使用 `react-router` 的 `Link` 或 `useNavigate` 跳转。

最近调用：

- 每行显示 `e.userName · featureName(e.feature) · ${modelLabel(e)}`，
  状态 `SUCCESS` 显示“成功”，成本 `fmtUsd(e.estimatedCost)`。
- 右上角加「查看」链接到
  `/admin/ai-cost/events?days=...&provider=...&model=...&modality=...`。

模型价格视图：

- 保留现有价格表格与增删改同步，不改。

### H. 独立页一：用户用量（`frontend/src/pages/admin/AiCostUsersPage.tsx`）

- 从 `useSearchParams()` 读 `days`、`provider`、`model`、`modality`、`page`、`size`。
- 缺省：`days=7`，模型筛选为空（全部），`page=0`，`size=20`。
- 调 `aiCostApi.users({ days, provider, model, modality, page, size })`。
- 表格列：用户名、调用数、输入 Token、输出 Token、成本、成功率。
- 顶部「返回成本页」用 `useNavigate` 回 `/admin/ai-cost`；分页控件带
  `total`，翻页时更新 `page` 并写回 URL（保留其它查询参数）。

### I. 独立页二：最近调用（`frontend/src/pages/admin/AiCostEventsPage.tsx`）

- 同样从 `useSearchParams()` 读筛选与分页。
- 调 `aiCostApi.events({ days, provider, model, modality, page, size })`。
- 表格列：时间、用户、功能（中文）、Provider/模型、状态、耗时、输入 Token、
  输出 Token、成本。
- 分页与返回逻辑同用户用量页。

### J. 路由与常量

`frontend/src/constants/routes.ts` 增加：

```ts
aiCostUsers: '/admin/ai-cost/users',
aiCostEvents: '/admin/ai-cost/events',
```

`frontend/src/App.tsx`：

```tsx
const AiCostUsersPage = lazy(() => import('./pages/admin/AiCostUsersPage'));
const AiCostEventsPage = lazy(() => import('./pages/admin/AiCostEventsPage'));
// 在 admin/ai-cost 下方
<Route path="admin/ai-cost/users" element={<AiCostUsersPage />} />
<Route path="admin/ai-cost/events" element={<AiCostEventsPage />} />
```

### K. 验证要点

- 后端 `./gradlew :app:compileJava`；`AiCostReportService` 相关定向测试；
  若改到公共查询，跑全量 `./gradlew :app:test --no-daemon`。
- 前端 `cd frontend && pnpm run build`。
- 手工验收：全部分别与单模型结果一致；7/30/90 天切换时所有模块同步变化；
  趋势图连续日期且空态正确；用户名列正确回退；两个独立页分页与 URL 条件恢复。
