# 用户 AI 配额配置与价格编辑 / 同步优化

## 目的

- 管理员在后台按用户手动配置 AI 限流（RPM / TPM / 并发）与成本限制（日软上限 / 日硬上限 / 月硬上限）。
- 模型价格改为按表格中的行直接编辑，不再要求手工输入 provider / model。
- 手动同步价格真正生效，并明确返回本次使用的数据来源。

## 触发

- 管理员进入「AI 调用与成本 → 用户配额」页签，选择用户后编辑并保存配额。
- 管理员在「模型价格」页签点击某一行「编辑」，修改价格后保存。
- 管理员点击「立即同步」或在启动时灌价 / 每日定时任务触发价格同步。

## 规则与步骤

### 用户配额

1. 配额分为两层：用户全局（`feature=null`）与按功能（`feature=功能名`）。
2. 限流覆盖存 `ai_user_rate_limit_override`；成本覆盖继续存 `ai_user_budget_override`。
3. 保存语义：请求字段非 null 表示设置，null 表示清除该维度；保存时先把当前生效行 `effective_to` 置为当前时间，再写入新行，保留历史。日软上限与日硬上限可以只设置一个，未设置的一侧继续沿用全局覆盖或系统默认值。
4. 生效优先级：功能级覆盖 > 用户全局覆盖 > 系统默认；限流结果本地缓存 60 秒，
   保存事务提交后主动失效该用户的缓存，当前实例下一次调用立即使用新值。
5. 管理员账号仍豁免，后台不展示 / 不允许给管理员配置配额。
6. 「恢复默认」= 结束该 scope 下的限流与成本覆盖行，回退到系统默认。

### 价格编辑

1. 每行「编辑」按钮把该行的 `id / provider / model / modality / 价格` 带入表单，provider / model / modality 只读。
2. 后端按 `id` 定位：手工价直接在原行更新；自动价先结束原行再新增一条手工价，保留自动价历史。
3. 仍保留「新增模型价格」作为受控入口：provider 从已配置列表选择，model 由所选 Provider
   自动带出且只读，modality 只能选该 Provider 支持的 TEXT / VISION / EMBEDDING。
   后端同样校验 provider、model、modality 三元组，不能绕过前端提交任意模型名。

### 价格同步

1. 数据源优先级：外部 URL（`app.ai.cost.prices.sync-url`）> 本地文件（`app.ai.cost.prices.local-path`）> classpath 内置 `prices/ai-model-prices.yml`。
2. 外部或本地源读取失败时回退下一级，并在返回结果里标注本次实际来源。
3. 手工价不被自动同步覆盖；自动价相同时跳过，不同时结束旧自动价并写入新自动价。
4. 同步接口返回 `{ applied, source }`，前端展示「已同步 N 条（来源：xxx）」。

## 结果

- 管理员可按用户精细控制限流与成本，普通用户调用立即按新配额执行。
- 价格编辑不再依赖手输模型名，自动价改价后变成手工价且可回退。
- 手动同步可看出实际应用条数与数据来源，不再是无声 no-op。

## 边界与异常

- 限流覆盖缓存 60 秒；保存事务提交后当前实例会立即失效该用户缓存，多实例部署时其他节点
  最迟 60 秒后生效。缓存上限 10000 条，超限清空过期项，必要时整体清空。
- 成本覆盖在每次操作预占时实时读库，无缓存，保存后下一次操作即生效。
- 用户不存在返回 `NOT_FOUND`；给管理员配置配额返回 `BAD_REQUEST`。
- 未知功能名返回 `BAD_REQUEST`。

## 附录：实现落点

### 数据库

- `app/src/main/resources/db/migration/V20261106__ai_user_rate_limit_override.sql`：新建限流覆盖表与索引。
- `app/src/main/resources/db/migration/V20261107__ai_user_budget_override_nullable_hard.sql`：
  允许日软 / 日硬上限分开配置，`hard_limit` 可为空。

### 后端

- 实体：`modules/aicost/model/AiUserRateLimitOverrideEntity`。
- 仓储：`AiUserRateLimitOverrideRepository`（`findActiveFeature` / `findActiveGlobal` / `findActiveByUser`）；`AiUserBudgetOverrideRepository` 增加 `findActiveByUser`。
- 解析：`AiUserRateLimitResolver.resolve(AiFeature, Long) -> RateLimits(rpm, tpm, concurrency)`，
  带 60 秒缓存；`invalidate(userId)` 清理该用户全部功能缓存。
- 网关：`AiUsageGate` 改用 `AiUserRateLimitResolver`，移除自身 feature 配置解析。
- 管理：`AiUserLimitAdminService.get / upsert / clear / features`；保存或清除后通过事务同步器
  在提交后调用 `AiUserRateLimitResolver.invalidate(userId)`。
- 控制器：`AiCostAdminController` 新增 `GET /features`、`GET /user-limits/{userId}`、`PUT /user-limits/{userId}`、`DELETE /user-limits/{userId}`。
- 价格编辑：`AiModelPriceAdminService.upsert` 按 `PriceUpsertRequest.id` 分支走 `edit` / `create`；
  `create` 通过 `LlmProviderConfigService.listProviders()` 校验 Provider 能力与模型是否匹配。
- 价格源：`AiModelPriceSource.loadWithSource() -> PriceSeeds(seeds, source)`；`AiModelPriceSyncService.syncNow() -> PriceSyncResult(applied, source)`。
- 配置：`AiCostProperties.Prices.localPath`，`application.yml` 增加 `app.ai.cost.prices.local-path`。

### 前端

- `api/aiCost.ts`：`PriceUpsertRequest.id?`、`PriceSyncResult`、`UserLimitsView`、`UserLimitsUpsertRequest`，新增 `features / userLimits / upsertUserLimits / clearUserLimits / syncPrices` 返回类型。
- `components/admin/UserQuotaPanel.tsx`：用户配额页签。
- `pages/admin/AiCostPage.tsx`：新增「用户配额」页签、价格行内编辑、同步结果提示；
  新增价格时读取 `llmProviderApi.list()`，Provider 用下拉框，模型自动填充。
