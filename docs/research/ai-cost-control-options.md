# AI 调用成本观测与控制方案调研

- 调研日期：2026-11-03
- 调研对象：OpenTelemetry GenAI、Spring AI Observability、LiteLLM、Portkey、Helicone、Vercel AI Gateway、Langfuse、OpenLLMetry、Grafana
- 资料口径：只采用官方文档、官方规范、官方仓库或源码，不采用二手博客结论
- 仓库基线：Spring Boot 4.1 / Java 25 / Spring AI 2.0.1 / PostgreSQL + pgvector / Redis / React
- 版本提醒：Spring AI 官方当前页面在调研时显示为 2.0.2；本文引用其当前文档说明机制，但涉及本项目 Spring AI 2.0.1 的实际指标名和标签时，应以本地运行结果为准，不能仅凭版本文档推断已经一致

## 1. 调研范围与结论边界

本文把问题拆成三层：

1. **观测层**：回答“谁、在哪个功能、调用了哪个模型、消耗多少 Token、耗时多少、成功还是失败、估算花了多少钱”。
2. **控制层**：回答“这次请求现在能不能放行；用户/租户的日预算、月预算、RPM、TPM、并发是否超限；超限后是拒绝、降级还是告警”。
3. **报表层**：回答“按用户、租户、功能、模型、供应商、时间维度看趋势和排行；哪些功能最贵；失败和 fallback 带来的额外成本是多少”。

这三层不能互相替代。OpenTelemetry、Spring AI、Langfuse、Grafana 主要解决观测和告警；真正的硬预算需要业务侧或网关侧在调用前做原子检查、预占和调用后对账。Vercel 官方明确把预算描述为 soft cap：请求开始时检查，跨越边界的请求仍会完成，因此可能轻微超支。https://vercel.com/docs/ai-gateway/observability-and-spend/budgets

同样，LiteLLM 官方在限流文档中说明，多实例 RPM/TPM 计数依赖 Redis；默认 Redis 不可达时会退回单实例内存计数，只有显式开启 fail-closed 才返回 503。https://docs.litellm.ai/docs/proxy/users

因此，本文的推荐不是“单独买一个可观测平台就完成成本控制”，而是“以自建业务账本和配额决策为核心，按需要叠加网关、Trace 平台和运维大盘”。

## 2. 本仓库现状对方案选择的影响

### 2.1 已有基础

- 仓库已经在 `LlmProviderRegistry` 中集中创建 `OpenAiChatModel` / `OpenAiEmbeddingModel`，并把 `ObservationRegistry` 传入模型；同时已有 `StructuredOutputInvoker` 作为结构化输出的统一重试入口。仓库核查路径：`app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java`、`app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java`。Spring AI 官方说明 `ChatModel`、`EmbeddingModel`、`ChatClient`、`VectorStore` 可以产生 Micrometer observations。https://docs.spring.io/spring-ai/reference/observability/index.html
- 仓库已有 Actuator 和 `micrometer-registry-prometheus`，`application.yml` 暴露了 `health,info,metrics,prometheus`；这适合先做 Prometheus + Grafana，但当前没有看到 OTLP tracing/export 配置。Spring Boot 官方把 metrics 与 tracing 分开配置，启用 tracing 不等同于启用 Prometheus 端点。https://docs.spring.io/spring-boot/reference/actuator/tracing.html
- 仓库已有 `CurrentUserHolder`、JWT 认证和异步 `SecurityContext` 传播能力，可以在同步调用和部分线程池场景拿到 `userId`。仓库核查路径：`app/src/main/java/interview/guide/modules/auth/security/CurrentUserHolder.java`、`app/src/main/java/interview/guide/common/async/BoundedExecutorFactory.java`。但当前没有统一的 `tenantId` 领域上下文，做多租户账本时应把 `tenantId` 作为显式字段传播，而不是日后从用户名反推。
- 仓库已经使用 PostgreSQL 和 Redis。PostgreSQL 适合做权威事件账本与报表；Redis 适合做短窗口 RPM/TPM、并发闸门和预算预占。LiteLLM 官方也把数据库作为预算权威数据源、把 Redis 作为跨实例限流和热计数层。https://docs.litellm.ai/docs/proxy/users

### 2.2 当前缺口

- `StructuredOutputInvoker` 当前自己的指标是 `app.ai.structured_output.invocations`、`attempts`、`latency`，没有 Token 或费用字段；它只能说明结构化输出调用次数和重试，不足以回答“这个用户今天花了多少钱”。Spring AI 的 Token 指标在 `ChatModel` observation 层产生，而不是业务 Service 自动知道“当前功能”和“当前用户”。https://docs.spring.io/spring-ai/reference/observability/index.html
- 当前大模型调用点分散在出题、试卷识别、知识库问答、语音面试、评估等模块；不能只给某一个 Service 加埋点，否则报表会漏掉直接调用 `ChatClient` 的路径。仓库核查路径包括 `LlmProviderRegistry`、`StructuredOutputInvoker` 以及 `KnowledgeBaseQueryService`、`PaperRecognitionService`、`VoiceInterviewEvaluationService` 等调用方。
- 如果只依赖 Prometheus 指标，用户 ID、租户 ID、功能名会变成高基数标签，长期会造成时间序列膨胀。Spring AI 文档明确区分低基数键进入 metrics、高基数键进入 traces。https://docs.spring.io/spring-ai/reference/observability/index.html
- 如果把业务账本放在异步日志或 Trace 采样里，失败请求、重试请求和 fallback 请求可能对不上账。OpenTelemetry 官方说明 sampling 会按策略丢弃部分 telemetry；tail sampling 可以按错误和延迟保留，但它是可观测性策略，不是计费账本。https://opentelemetry.io/docs/concepts/sampling/

## 3. OpenTelemetry GenAI 语义约定

### 3.1 它解决什么问题

OpenTelemetry GenAI 语义约定提供的是一套跨供应商的命名和结构：用一致的 operation、provider、model、token 和错误字段描述模型调用，使不同 SDK、网关和观测后端可以在同一套看板上比较。原入口页面已经迁移，当前规范仓库为 `open-telemetry/semantic-conventions-genai`。https://opentelemetry.io/docs/specs/semconv/gen-ai/ https://github.com/open-telemetry/semantic-conventions-genai

### 3.2 当前状态与核心指标

- GenAI 规范仍标记为 **Development**，因此字段名和指标名仍可能演进；不能把它当作已经冻结、永远不变的财务账本格式。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md
- 当前 token usage 规范建议使用 usage counter 作为总消耗和成本估算的主指标，包含 `gen_ai.client.inference.usage.input_tokens`、`output_tokens`、`cache_read.input_tokens`、`cache_write.input_tokens`、`reasoning.output_tokens` 等；per-operation histogram 只适合看单次分布和 p95/p99，不应拿来直接汇总总成本。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md
- `cache_read`、`cache_write`、`reasoning` 是 `input_tokens` / `output_tokens` 的子集，而不是独立再相加的第四类总量；如果业务账本同时记录总量和细分桶，必须有明确的不重叠口径。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md
- 当供应商同时返回“实际使用的 token”和“可计费 token”时，规范要求 instrumentation 优先报告可计费 token；这提醒我们不能仅凭本地 tokenizer 估算账单。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md
- 规范还建议记录成功和失败推理操作的 usage 贡献；失败请求并不等于零成本，尤其是已经产生部分生成、超时、流中断或供应商计费但客户端未拿到完整响应的场景。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md
- embedding 是独立场景，当前规范单独定义 embedding operation/input token instrument；不要把 embedding 的 token 口径硬套到 chat completion 的 input/output 口径。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/client-embeddings.md

### 3.3 费用属性与业务维度

- 本次检索到的 GenAI token 规范只定义了 token usage 和相关属性，没有定义一个跨所有供应商统一的 USD 费用属性。因此“费用”仍需要平台自己的价格表、价格版本和计算规则；OpenTelemetry 提供用量事实，不替你决定价格。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md
- `gen_ai.operation.name`、`gen_ai.provider.name`、`gen_ai.request.model`、`gen_ai.response.model` 等属性适合描述模型调用；但“这个请求来自哪个业务功能、哪个产品租户、哪个站内用户”不是 GenAI 标准通用字段，需要应用侧自定义 span/log/event 属性。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md
- 由于用户 ID 和租户 ID 是高基数数据，应优先进入 trace 或 PostgreSQL 账本，不应直接作为 Prometheus 指标标签。Spring AI 官方对低基数和高基数 observation key 的分流说明支持这个设计。https://docs.spring.io/spring-ai/reference/observability/index.html

### 3.4 适用结论

OpenTelemetry GenAI 适合作为“字段命名和跨系统交换协议”的参考，不适合作为唯一的财务账本。合理的落法是：

1. 用 GenAI 语义约定统一 provider/model/operation/token 这些通用维度；
2. 用业务自定义属性补充 `tenant_id`、`user_id`、`feature_code`、`request_id`；
3. 用 PostgreSQL `ai_call_events` 记录每次调用的最终事实；
4. 用 Prometheus/Grafana 或 Trace 后端看趋势和排障，不把采样后的 telemetry 当作结算依据。

## 4. Spring AI Observability 与 Micrometer / OTel 集成

### 4.1 自动能拿到什么

Spring AI 官方 Observability 文档说明，`ChatClient`、`ChatModel`、`EmbeddingModel`、`ImageModel`、`VectorStore` 都有 metrics 和 tracing 能力。https://docs.spring.io/spring-ai/reference/observability/index.html

- `ChatClient` observation：记录调用耗时、完成次数、in-flight 数量和 stream 标记；它适合看框架层入口和端到端耗时。https://docs.spring.io/spring-ai/reference/observability/index.html
- `ChatModel` observation：记录模型供应商执行耗时、完成次数、in-flight 数量，以及 Token usage。https://docs.spring.io/spring-ai/reference/observability/index.html
- 当前官方文档列出的 Prometheus 指标包括 `gen_ai_chat_client_operation_seconds_*`、`gen_ai_client_operation_seconds_*`、`gen_ai_client_operation_active_count` 和 `gen_ai_client_token_usage_total`。其中 Token 指标按 `gen_ai_token_type=input|output|total` 区分。https://docs.spring.io/spring-ai/reference/observability/index.html
- ChatModel observation 可带 `gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`、`gen_ai.usage.total_tokens`、`gen_ai.usage.cache_creation.input_tokens`、`gen_ai.usage.cache_read.input_tokens`、`finish_reasons`、请求/响应模型等。https://docs.spring.io/spring-ai/reference/observability/index.html
- Prompt 和 completion 默认不导出、不记录；即使做成本观测，也没有必要开启敏感内容日志。官方默认值是 `log-prompt=false`、`log-completion=false`，并提示开启有泄露风险。https://docs.spring.io/spring-ai/reference/observability/index.html
- `VectorStore` 有 `db.vector.client.operation` 指标，官方支持的 `db.system` 值包含 `pg_vector`；可以观测 pgvector 的 add/delete/query 延迟和调用量，但这不是模型 Token 成本。https://docs.spring.io/spring-ai/reference/observability/index.html
- 当前 OpenAI chat 文档还说明了一个 streaming 细节：OpenAI 流式调用的 HTTP span 可能没有正确挂在 chat-model span 下面；其他同步调用和 Anthropic 流式调用的嵌套行为不同。因此要看全链路延迟时，不能假设所有供应商的 span 树完全一致。https://docs.spring.io/spring-ai/reference/observability/index.html

### 4.2 Spring AI 自动观测的边界

Spring AI 自动观测能回答“这个 ChatModel 调用了多少 token、耗时多少”，但不能自动回答以下业务问题：

- 这次调用属于哪个产品功能；
- 这次调用是哪个租户和哪个站内用户发起的；
- 这次调用是否被 `StructuredOutputInvoker` 重试了多次；
- 这次调用是主模型成功，还是 fallback 到另一个模型后才成功；
- 这次调用对应的价格版本、币种和最终费用；
- 这次调用是否命中了应用层语义缓存或供应商 Prompt Cache。

前四项必须通过业务上下文传播和账本补齐；后两项需要额外的价格表与缓存命中事件。OpenTelemetry 的 usage counter 和 Spring AI 的 observation 能提供基础事实，但不能替代业务事件。https://docs.spring.io/spring-ai/reference/observability/index.html

### 4.3 ChatResponse 里的 usage 如何使用

Spring AI 的 Chat Response / Usage Handling 文档提供了从响应元数据读取 Token 用量的入口。调用结束后把 `input/output/total` 及缓存细分桶写入账本，比只从 Prometheus 反查更可靠，因为它能和 `request_id`、`tenant_id`、`user_id`、`feature_code` 一一对应。https://docs.spring.io/spring-ai/reference/api/usage-handling.html

建议把响应 usage 作为**调用事实**，把 Spring AI 自动指标作为**交叉校验**：

- 账本记录每次请求的 usage 和价格；
- Prometheus 的 `gen_ai_client_token_usage_total` 用于看整体趋势、异常突增和供应商差异；
- 如果两边长期差异过大，排查是否漏记了流式结束、重试、fallback、embedding 或失败请求。https://docs.spring.io/spring-ai/reference/observability/index.html

### 4.4 版本与兼容性提醒

调研时 Spring AI 当前官方页面显示为 2.0.2，而项目依赖是 2.0.1。不能直接假设 2.0.1 的每个指标名、标签集合、streaming observation 行为都和当前页面一致；上线前应在项目的 2.0.1 依赖上抓一次实际 Prometheus 指标和 trace，确认：

- Token 指标名是否确实是 `gen_ai_client_token_usage_total`；
- `input/output/total` 标签是否都存在；
- cache read/write token 是否能在 observation 中拿到；
- OpenAI、DashScope 兼容接口和流式调用的 span 是否完整；
- `ObservationRegistry` 是否在所有 provider/client 构造路径上都被传入。

仓库已经在 `LlmProviderRegistry` 的多个模型构造路径传入 `ObservationRegistry`，但这仍应以实际运行核验为准。Spring AI 官方说明 observability 依赖 Spring Boot 的 metrics/tracing 基础设施，而不只是存在一个 bean。https://docs.spring.io/spring-ai/reference/observability/index.html

## 5. AI Gateway / 代理方案对比

### 5.1 LiteLLM

#### 解决的问题

LiteLLM 是一个可自托管的统一模型网关，官方提供跨 provider 的 routing、fallback、retry、cooldown、Redis 共享状态、成本/延迟路由等能力。它可以把“模型选择、故障切换、供应商差异”从业务代码中移走。https://docs.litellm.ai/docs/routing

#### 预算与限流机制

- LiteLLM 支持 virtual key、user、team、team member、end-user 等预算作用域，并支持 RPM、TPM、最大并发请求。https://docs.litellm.ai/docs/proxy/users https://docs.litellm.ai/docs/proxy/virtual_keys
- 预算必须有 PostgreSQL 等数据库；官方明确说明 DB-less 部署下 `max_budget` 会 fail open，global/key/team/user 预算不能可靠执行。这一点对“成本失控”目标是决定性约束。https://docs.litellm.ai/docs/proxy/users
- `max_budget` 可以配合 `budget_duration` 重置，也可以给同一个 key 设置多个时间窗口，例如日预算和月预算同时生效。https://docs.litellm.ai/docs/proxy/users
- 默认开启 budget reservation：按请求体、模型价格和 `max_tokens` / `max_completion_tokens` 估算最大成本，先预占预算，再在响应后替换为实际成本；这样能防止并发请求同时穿透预算。https://docs.litellm.ai/docs/proxy/users
- 如果需要 Redis 异常时仍保持硬预算，可以开启 `fail_closed_budget_enforcement`。开启后会根据数据库验证当前 spend；Redis 和数据库都无法验证时返回 503，而不是继续放行。https://docs.litellm.ai/docs/proxy/users
- 多实例限流使用 Redis 共享计数；默认 Redis 不可达时会退回单实例内存，显式开启 `fail_closed_rate_limit_enforcement` 后会以 503 拒绝无法验证的限流请求。https://docs.litellm.ai/docs/proxy/users

#### 路由、缓存与降级

- Routing 支持 fallback、retry、cooldown、Redis、成本/延迟路由；这能降低单一 provider 故障导致的业务失败。https://docs.litellm.ai/docs/routing
- Auto Router 可以按请求复杂度分流到便宜模型或更强的推理模型，但官方页面把它标为 Add-on，不能假设它是所有部署都默认包含的免费能力。https://docs.litellm.ai/docs/auto_router
- Prompt caching 支持多家 provider，并按 cache read/write token 进行价格计算；这有利于把供应商 Prompt Cache 的折扣传递到账本。https://docs.litellm.ai/docs/completion/prompt_caching
- 语义缓存只适合单轮、低变化的 prompt。官方明确警告：多轮或 agentic 流量会把旧响应重放，导致工具调用循环；应用层应考虑 exact cache 或按 agent key 关闭语义缓存。https://docs.litellm.ai/docs/proxy/caching_semantic
- 语义缓存默认按 virtual key/team/org 隔离；多个最终用户共用一个 key 时可能共享语义桶。要按用户隔离，需要配置 `semantic_cache_scope: end_user`，并确保传递 end-user id。https://docs.litellm.ai/docs/proxy/caching_semantic

#### 与本仓库的适配

适配点较好：Spring AI 仍然通过 OpenAI 兼容的 `ChatClient` 调用，业务 Service 不需要逐个感知 provider；LiteLLM 负责模型别名、路由、fallback、预算和限流。仓库现有的 Redis 可以复用，PostgreSQL 也可以作为 LiteLLM 预算数据库或独立账本。

代价是多一套 Python 服务、PostgreSQL 数据库、Redis 配置和升级运维；同时所有请求多一个网络跳点，流式响应、工具调用、视觉输入、结构化输出和 DashScope 兼容性需要逐项压测。即使引入 LiteLLM，仍建议保留本仓库的 `ai_call_events`，因为 LiteLLM 更懂模型调用，而本仓库更懂租户、功能、课程和业务结算口径。

### 5.2 Portkey

#### 解决的问题

Portkey AI Gateway 官方提供缓存、fallback、条件路由、重试、熔断、负载均衡、预算和限流等网关能力。它适合把 provider 访问治理、治理策略和观测放在统一入口。https://portkey.ai/docs/product/ai-gateway

#### 预算与限流机制

- API Key 可以设置 USD 预算或 token 预算，并支持告警阈值、周/月重置。https://portkey.ai/docs/product/administration/enforce-budget-and-rate-limit
- 限流可以按请求数或 Token，时间窗口支持每分钟、每小时、每天；达到限制后请求被拒绝。https://portkey.ai/docs/product/administration/enforce-budget-and-rate-limit
- 告警阈值只通知，不会在达到总预算前阻断；总预算达到后才阻止继续使用。https://portkey.ai/docs/product/administration/enforce-budget-and-rate-limit
- 但 API Key 级预算/限流页面明确标注：仅 Enterprise 计划和 select Pro 客户可用，不能把该能力默认为所有免费/普通套餐自带能力。https://portkey.ai/docs/product/administration/enforce-budget-and-rate-limit

#### 用户归因与多租户

- Portkey 的 `_user` metadata 可用于观测和成本归因；metadata 值是字符串，官方限制其长度不能超过 128 个字符。https://portkey.ai/docs/product/observability/metadata
- 对站内最终用户做硬预算时，不能只写 `_user` metadata 就认为已经按用户限流；更稳妥的落法是按租户/产品套餐创建独立 virtual key，或验证当前套餐是否支持所需的 end-user/workspace 级控制。官方预算页面强调 API Key、workspace、organization 级治理。https://portkey.ai/docs/product/administration/enforce-budget-and-rate-limit

#### 与本仓库的适配

Portkey 的优势是网关治理能力完整，尤其是 fallback、条件路由和可观测性；劣势是关键预算能力受套餐限制，而且自托管/企业部署会引入额外许可和运维决策。若平台规模扩大、需要多 provider SLA 路由，Portkey 可作为候选；若当前首要目标只是低成本控制内部用户，先自建账本更可控。

### 5.3 Helicone

#### 解决的问题

Helicone 提供 OpenAI 兼容的 AI Gateway 和可观测能力，官方路由会优先选择最便宜的可用 provider，并在 429、401、400、408、500+ 等错误上自动尝试下一个 provider。https://docs.helicone.ai/gateway/provider-routing

#### 预算与限流机制

- 自定义限流通过 `Helicone-RateLimit-Policy` 设置，单位可以是 `request` 或 `cents`，窗口最小 60 秒。https://docs.helicone.ai/features/advanced-usage/custom-rate-limits
- 限流作用域可以是 global、per-user 或 custom property；per-user 依赖 `Helicone-User-Id`。https://docs.helicone.ai/features/advanced-usage/custom-rate-limits
- 超限返回 429；响应头会给出 limit、policy、remaining，便于客户端退避。https://docs.helicone.ai/features/advanced-usage/custom-rate-limits
- 官方页面当前把“按 token 限流”和“同一请求应用多条限流策略”列为 Coming Soon；因此不能把 Helicone 当前能力描述成已经支持完整的 TPM 或多窗口预算。https://docs.helicone.ai/features/advanced-usage/custom-rate-limits
- Helicone 的 `Helicone-User-Id` 可用于用户维度指标和成本归因，但本次检索未在任何官方页面看到它对“累计 per-user hard budget”的明确承诺；应把它理解为周期性限流/成本窗口，而不是完整的财务硬上限。https://docs.helicone.ai/features/advanced-usage/user-metrics

#### 缓存与路由

- Helicone 缓存是按完整响应键的精确缓存，官方说明可设置 cache seed/namespace 隔离不同用户；它不是语义相似缓存。https://docs.helicone.ai/features/advanced-usage/caching
- 默认 gateway 会选择 cheapest provider，并在 provider 故障或限流时 fallback；这对成本优化和可用性有帮助，但必须在业务侧验证便宜模型对题目质量的影响。https://docs.helicone.ai/gateway/provider-routing

#### 与本仓库的适配

Helicone 接入成本相对低，适合快速获得 trace、成本看板和 per-user 周期限流；但它的预算模型更像“窗口限流”，不是自建账本那种“月累计金额 + 事务级预占 + 失败重试成本”。如果平台只需要防止滥用，它够用；如果要做“每个租户每个月最多 50 元、并发下不能超支”，仍需自建控制层或引入更强的网关预算。

### 5.4 Vercel AI Gateway

#### 解决的问题

Vercel AI Gateway 是托管式网关，提供请求、provider、延迟、token、费用和 routing attempt 观测。它适合已经在 Vercel 平台部署、希望快速接入多家模型并集中看成本的产品。https://vercel.com/docs/ai-gateway

#### 预算和告警机制

- Budget 支持 team、project、API key、user 四个作用域；一次请求会同时计入所属作用域，任何一个作用域余额不足都会拒绝。https://vercel.com/docs/ai-gateway/observability-and-spend/budgets
- 超限后返回 HTTP 402；可以在 50%、75%、100% 设置邮件告警，阈值告警不会在下限触发阻断。https://vercel.com/docs/ai-gateway/observability-and-spend/budgets
- 官方明确说明 budget 是 soft cap，不是硬上限：请求开始时检查，跨越边界的请求仍会完成，总花费可能略超预算。https://vercel.com/docs/ai-gateway/observability-and-spend/budgets
- “user budget”对应 team member，而不是任意站内最终用户；只有归属于该成员的 API Key 才计入该 user budget。BYOK 的 provider key 支出不计入预算。https://vercel.com/docs/ai-gateway/observability-and-spend/budgets

#### 缓存、fallback 与多租户

- Vercel 提供自动 prompt caching 和顺序模型 fallback；模型 fallback 可以减少某一家 provider 故障造成的失败。https://vercel.com/docs/ai-gateway/models-and-providers/automatic-caching https://vercel.com/docs/ai-gateway/models-and-providers/model-fallbacks
- 对 SaaS 产品里的最终用户，不能直接把 Vercel 的 team member 当成业务用户；需要在应用侧把业务租户/用户映射到 API key、project 或 team 的治理边界，或者只把 Vercel 用于内部/平台级预算。

#### 与本仓库的适配

Vercel 的观测和 budget UX 成熟，适合作为 SaaS 托管方案，但它是外部托管服务，且 soft cap 不能保证“并发场景绝对不超支”。如果本仓库部署在自己的服务器上，引入 Vercel 会增加外部依赖、数据跨境和出口网络问题；更适合作为对照方案，而不是默认首选。

## 6. Langfuse 与 OpenLLMetry 等观测/评估平台

### 6.1 Langfuse

#### 解决的问题

Langfuse 主要解决 LLM trace、prompt/version 管理、评估、用户/会话分析、Token 与成本追踪。它适合回答“为什么这次回答质量变化、这个模型版本贵多少、哪个用户或功能最常使用”。

#### 成本模型

- Langfuse 为每个 LLM generation/embedding 记录 usage details 和 cost details，成本可以来自调用方直接 ingest，也可以根据模型定义和单价推断。https://langfuse.com/docs/observability/features/token-and-cost-tracking
- 支持自定义模型、价格和 pricing tier，能按上下文长度、模型参数或 metadata 选择价格层。https://langfuse.com/docs/observability/features/token-and-cost-tracking
- usage details 是互斥桶，`input` 不应包含 `input_cached_tokens`，否则会和缓存 token 重复计费；官方要求把缓存读/写 token 从总 input 中拆出来。https://langfuse.com/docs/observability/features/token-and-cost-tracking
- 只有 generation 和 embedding 类型的 observation 支持 usage/cost；普通 span 不是费用载体。https://langfuse.com/docs/observability/features/token-and-cost-tracking

#### 用户维度与控制边界

- 用户级统计依赖在 trace 中传播 `userId`；它适合用户分析、会话分析和评估，不等于按用户执行配额。https://langfuse.com/docs/observability/features/users
- Langfuse 官方有 Spring AI + OpenTelemetry 集成说明，但示例使用的 Spring AI 版本偏旧；接入 Spring AI 2.0.1 前应核对 OTel instrumentation、span 名称和 usage 字段，不能照抄示例后直接上线。https://langfuse.com/integrations/frameworks/spring-ai
- 自托管 Langfuse v4 官方部署文档涉及 PostgreSQL、Redis/Valkey、ClickHouse、S3/blob 等多个组件，资源占用明显高于“只在现有 Spring Boot 里加一个 Prometheus 端点”。https://langfuse.com/self-hosting

#### 与本仓库的适配

Langfuse 很适合做质量回归、Prompt 版本对比、用户会话和成本分析；不建议用它替代 Redis/PostgreSQL 的业务配额引擎。若引入，优先使用 Langfuse Cloud 或独立实例，并且对题干、答案、学生信息等敏感内容做脱敏；Spring AI 默认不导出 prompt/completion，正好可以作为安全基线。https://docs.spring.io/spring-ai/reference/observability/index.html

### 6.2 OpenLLMetry

OpenLLMetry 是 Apache-2.0 的 OpenTelemetry 扩展，目标是给 LLM SDK/框架提供自动 instrumentation，并通过标准 OTLP 发送到可观测后端。它适合把 trace 统一到 OTel 生态，而不是单独承担业务配额。https://github.com/traceloop/openllmetry

但官方 README 当前列出的 SDK/框架支持仍以 Python 生态为主，Java/Spring Boot 不是它的首选目标。对本仓库而言，Spring AI 已经有原生 Micrometer/OTel 接入路径，优先使用 Spring AI 自带 observation，再补业务属性，通常比引入一个以 Python 为中心的 instrumentation 层更直接。https://github.com/traceloop/openllmetry https://docs.spring.io/spring-ai/reference/observability/index.html

### 6.3 Grafana / Prometheus

Grafana 官方支持 OTLP、Prometheus、日志和告警，是很好的运维观测和告警层。它可以展示请求量、延迟、Token 趋势、错误率和预算使用率，但不是一个在业务请求前执行配额决策的组件。https://grafana.com/docs/opentelemetry/

把用户 ID、租户 ID、题目 ID 等高基数维度直接放进 Prometheus label，会造成 series cardinality 爆炸；真正应按用户精确审计的账本应放 PostgreSQL，Prometheus 只保留低基数聚合维度和告警指标。Spring AI 官方也明确区分低基数 metrics 和高基数 traces。https://docs.spring.io/spring-ai/reference/observability/index.html

## 7. 成本控制具体机制拆解

| 机制 | 能解决什么 | 关键实现点 | 主要风险 | 官方来源 |
|---|---|---|---|---|
| Per-user / per-key budget | 给用户、团队、租户或虚拟 Key 设置金额/Token 上限 | 预算周期、重置时区、币种、作用域和超限动作 | 只做事后统计无法阻止并发穿透；必须预占和对账 | https://docs.litellm.ai/docs/proxy/users |
| Spend cap 硬上限 | 预算耗尽时拒绝请求，而不是只发告警 | 调用前检查、原子预占、异常恢复、fail-closed | 估算过高会误杀，估算过低会并发超支；数据库/Redis 故障策略必须定义 | https://docs.litellm.ai/docs/proxy/users |
| Spend cap 软上限 | 用低复杂度换取体验，允许边界请求完成 | 请求开始时检查，跨边界请求允许完成 | 可能轻微超支，不能承诺严格预算 | https://vercel.com/docs/ai-gateway/observability-and-spend/budgets |
| RPM / TPM / 并发限流 | 防突发流量、保护供应商配额和系统资源 | 分钟窗口、输入/输出/总 Token 口径、跨实例共享 Redis | 不能替代月预算；Redis 异常时的 fail-open/fail-closed 必须明确 | https://docs.litellm.ai/docs/proxy/users |
| Fallback | 某 provider 失败或限流时保持可用 | 顺序 fallback、成本/延迟路由、cooldown 和重试上限 | fallback 到更贵模型会增加成本；每次失败重试本身也可能计费 | https://docs.litellm.ai/docs/routing |
| Cheap-first / 模型路由 | 让简单请求走便宜模型，复杂请求走强模型 | 质量门槛、分类器、A/B 评测、按功能配置 | 质量问题可能被短期成本下降掩盖；Auto Router 在 LiteLLM 是 Add-on | https://docs.litellm.ai/docs/auto_router |
| Provider prompt caching | 复用稳定的系统提示、长资料前缀，降低输入成本 | 稳定前缀、缓存写入成本、缓存读/写 Token 分开计费 | 前缀变化会失去命中；必须把缓存读写价格写进价格表 | https://help.aliyun.com/zh/model-studio/context-cache |
| 精确响应缓存 | 相同请求直接复用完整响应 | 请求哈希、租户/用户命名空间、过期和清理 | 只能命中完全相同的请求；不能解决语义相似问题 | https://docs.helicone.ai/features/advanced-usage/caching |
| 语义缓存 | 相似问题复用响应，降低重复调用 | 向量相似度阈值、按用户/租户隔离、单轮限制 | 多轮/agent 会重放旧响应；跨用户共享可能泄露/串答案 | https://docs.litellm.ai/docs/proxy/caching_semantic |

### 7.1 为什么硬预算需要“预占 + 对账”

LiteLLM 的 budget reservation 机制说明：如果只根据已记录 spend 判断，多个并发请求会同时认为预算还有空间；预占最大可能成本后再替换为实际成本，才能把并发风险压到最低。官方甚至明确说明，关闭 reservation 会允许并发请求超过预算。https://docs.litellm.ai/docs/proxy/users

这给本仓库的直接启示是：

1. 请求开始前先按 `max_tokens`、输入估算和模型价格计算 reservation；
2. 用 Redis Lua 或等价原子操作检查“已用 + 预占 + 本次预留”是否超过日/月预算；
3. 请求结束后用响应 usage 替换预占，写入 PostgreSQL 权威账本；
4. 失败或超时也记录 attempted event，并释放或结算预占；
5. Redis 不可用时的策略必须配置化：普通功能可 fail-open 并告警，成本敏感功能应 fail-closed。

这里的“硬上限”不是绝对数学保证，而是“在约定故障模型下尽量拒绝超出预算的请求”。Vercel 的 soft cap 就是明确不做这种并发和边界保证。https://vercel.com/docs/ai-gateway/observability-and-spend/budgets

### 7.2 为什么 Token 指标不能直接等于账单

- 供应商可能对缓存读、缓存写、reasoning token、批量任务、图片/音频输入采用不同价格；
- 供应商可能报告“实际 token”和“计费 token”两个口径，规范要求优先使用可计费口径；
- 供应商价格会变，历史账单必须保存调用发生时的 `price_version`；
- 语义缓存命中不会产生新的模型调用，但仍应记录一次“缓存命中事件”，否则成本报表会高估节省、请求报表会低估流量。

OpenTelemetry 的 token 指标可以告诉你有多少 token，Langfuse/LiteLLM 可以用价格表推断成本，但最终应由本仓库的价格目录和事件账本决定结算口径。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md https://langfuse.com/docs/observability/features/token-and-cost-tracking

### 7.3 Prompt Cache 与语义缓存不是同一件事

- Prompt Cache 通常是供应商侧针对稳定前缀的缓存，OpenAI、DashScope 等 provider 会按缓存读/写 token 计价；重点是“前缀稳定”，不是“问题相似”。
- 语义缓存是平台侧/网关侧用 embedding 找相似请求，可能返回旧答案；LiteLLM 官方明确提醒多轮和 agentic 场景不适合。

以 DashScope 为例，官方上下文缓存文档说明：隐式缓存自动生效，命中部分约为输入价的 20%；显式缓存命中约为 10%，缓存写入通常为 125%，最小 1024 token，显式缓存约 5 分钟，并在 usage 中返回 `cached_tokens` / `cache_creation_input_tokens`。这些是供应商计费口径，必须映射到本仓库价格表，不能只记一个总 input token。https://help.aliyun.com/zh/model-studio/context-cache

## 8. A/B/C/D 四种落点对比

| 方案 | 自建成本 | 维护成本 | 是否满足按用户计费/配额 | 多租户适配 | 主要优点 | 主要局限 | 官方依据 |
|---|---:|---:|---|---|---|---|---|
| A. 自建 `ai_call_events` + Redis 配额 + 站内报表 | 中 | 中 | 可以，且可定义业务规则 | 强，可显式存 `tenant_id` / `user_id` / `feature_code` | 最懂本仓库业务；可精确处理重试、fallback、缓存、价格版本；不增加外部网络跳点 | 需要自己实现原子预占、失败恢复、价格目录和报表；设计不严会漏记 | https://docs.spring.io/spring-ai/reference/api/usage-handling.html |
| B. 引入 LiteLLM 统一代理 | 中高 | 中高 | 强；virtual key/user/team/end-user 预算和 RPM/TPM 较完整 | 强；支持 user/team/end-user 作用域，但业务租户仍需映射 | 路由、fallback、预算、Redis 共享限流、成本追踪成熟 | 多一套 Python + PostgreSQL + Redis；DB-less 预算 fail open；流式/工具/视觉兼容需测试 | https://docs.litellm.ai/docs/proxy/users |
| C. Langfuse / Helicone 等平台 | 中 | 中高（自托管 Langfuse 更高） | Langfuse 主要是观测/分析；Helicone 有周期限流但未见累计硬预算承诺 | 适合 trace/用户分析；硬配额仍需应用侧或网关侧 | 上手快，trace、成本、评估和用户维度分析强 | 不能直接替代权威账本；自托管组件多；许可/套餐和数据边界需评估 | https://langfuse.com/self-hosting https://docs.helicone.ai/features/advanced-usage/custom-rate-limits |
| D. 仅 OTel GenAI + Grafana | 低到中 | 中 | 不满足严格配额执行 | 可展示聚合趋势，但用户/租户高基数不适合做指标标签 | 标准化、供应商中立、运维告警成熟 | 采样和指标聚合不适合作为财务账本；没有调用前决策能力 | https://opentelemetry.io/docs/concepts/sampling/ https://grafana.com/docs/opentelemetry/ |

### 8.1 选型判断

- **A 是当前最稳的基础方案。** 本仓库已有 PostgreSQL、Redis、用户上下文和统一 `ChatClient` 构造入口，先做业务账本和配额引擎，投入可控，且不会被某个网关的套餐或许可锁住。Spring AI 已提供 Token observation 和 usage 元数据，能显著降低自建埋点成本。https://docs.spring.io/spring-ai/reference/api/usage-handling.html
- **B 适合在 provider 数量和路由复杂度上升后引入。** LiteLLM 的能力边界清晰、官方文档对预算预占和 fail-closed 说明充分，但 DB-less fail-open 意味着不能“只装一个 LiteLLM 进程就认为预算安全”。https://docs.litellm.ai/docs/proxy/users
- **C 适合作为观测/评估增强，不适合作为唯一控制面。** Langfuse 的费用字段和 pricing tier 很成熟，但用户预算不是它的核心产品承诺；Helicone 的 per-user 限流可以挡住短窗口滥用，但当前官方未给出完整的累计 hard budget 语义。https://langfuse.com/docs/observability/features/token-and-cost-tracking https://docs.helicone.ai/features/advanced-usage/user-metrics
- **D 适合作为 P0 的工程监控底座，不应作为最终成本控制系统。** OTel token metric 和 Grafana 告警能快速发现异常，但采样、标签基数和缺少事务状态决定了它们不能承担结算和硬配额。https://opentelemetry.io/docs/concepts/sampling/

## 9. 面向本仓库的建议架构：观测层 + 控制层 + 报表层

### 9.1 观测层：必须自建“业务语义”，复用现成标准

建议做：

- 以 OpenTelemetry GenAI 的 provider/model/operation/token 命名作为基础规范；
- 继续使用 Spring AI 的 Micrometer Observation，作为 ChatModel/EmbeddingModel/VectorStore 的底层信号；
- 在所有 `ChatClient` 构造路径上统一注入 observation 和业务 Advisor；
- 在 trace/span 中附加 `tenant_id`、`user_id_hash`、`feature_code`、`request_id`、`conversation_id`、`provider`、`model_requested`、`model_actual`、`retry_attempt`、`fallback_from`；
- 不默认记录 prompt/completion；成本观测不需要把学生答案和题目内容写入日志。https://docs.spring.io/spring-ai/reference/observability/index.html

必须自建的原因：Spring AI 能自动带模型、token、耗时和错误，但不知道本仓库的“哪个功能、哪个租户、哪个业务动作”值多少钱。标准 GenAI 语义也没有定义本仓库的业务 feature code。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md

### 9.2 控制层：必须自建四件事

#### 第一件：业务账本

建议新增 `ai_call_events`，把它作为权威事实表，而不是把 Prometheus 或 Langfuse 当账本。建议字段：

```text
event_id
request_id / trace_id
tenant_id
user_id
feature_code
operation_name
provider
model_requested
model_actual
status（STARTED / SUCCEEDED / FAILED / CANCELLED）
error_code
latency_ms
input_tokens
output_tokens
cache_read_tokens
cache_write_tokens
reasoning_tokens
retry_attempt
fallback_from
cache_hit
price_version
currency
cost_micros
created_at / finished_at
```

关键规则：

- 每次模型调用都要有一条事件，包括失败和重试；OpenTelemetry 建议记录成功和失败推理的 usage 贡献。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md
- `tenant_id`、`user_id`、`feature_code` 必须在调用入口捕获；异步消费、流式回调和线程池场景要显式传播，不能等响应回来再从 request 取。
- 价格表要单独版本化，事件只保存 `price_version` 和快照金额；Langfuse 的模型定义、pricing tier 也说明价格需要按模型和 usage 类型匹配，而不是把价格硬编码死在事件里。https://langfuse.com/docs/observability/features/token-and-cost-tracking
- 费用统一用整数 micros 或最小货币单位存储，避免浮点误差；数据库报告层再格式化为元/美元。

#### 第二件：Redis 热配额

建议 Redis 负责：

- RPM、TPM、并发 in-flight；
- 日/月预算的快速预占；
- 预占 TTL 和异常回收；
- 与 PostgreSQL 的定期对账。

Redis 适合作为热路径，但不能成为唯一账本：LiteLLM 官方明确说预算必须有数据库，DB-less 场景会 fail open。https://docs.litellm.ai/docs/proxy/users

#### 第三件：预算策略

建议最小策略集：

- 用户/租户月度软预算：50%、80%、100% 告警；
- 用户/租户月度硬预算：对高成本功能启用 fail-closed；
- 全局日预算：防止全平台异常刷量；
- 功能级预算：试卷识别、长文问答、语音评估等高成本功能单独限流；
- provider/模型级预算：避免 fallback 后成本失控；
- 单请求最大输入/输出 Token 和单次最大费用；
- 重试/fallback 次数上限：失败重试和跨模型 fallback 都写入同一条业务链路。https://docs.litellm.ai/docs/proxy/users

策略的默认建议：

- 普通练习/问答：软告警 + RPM/TPM；达到月度预算后降级到便宜模型或排队；
- 试卷识别/整卷生成/长文评估：硬预算 + 预占，避免一次大请求耗尽整月预算；
- 管理后台/教师端：可配置更高配额；
- 免费用户/试用用户：默认低预算和严格并发；
- 未知租户或缺失身份：拒绝或落到全局安全配额，不能默认无限。

#### 第四件：价格与缓存规则

- Prompt Cache、语义缓存、精确缓存命中都应记录“节约用量”和“实际调用用量”两个字段；
- 提供商 cache read/write 的 token 要按子集或互斥桶明确转换，避免同时把 `input` 和 `cache_read` 再相加。OpenTelemetry 说明 cache read 是 input 的子集，Langfuse 说明其账本要求互斥桶，两者语义不同，必须有转换适配层。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md https://langfuse.com/docs/observability/features/token-and-cost-tracking
- 语义缓存必须按租户/用户隔离；LiteLLM 官方警告默认按 key/team/org 共享 bucket，`semantic_cache_scope: end_user` 才能隔离最终用户。https://docs.litellm.ai/docs/proxy/caching_semantic

### 9.3 报表层：站内报表为主，Grafana/Langfuse 为辅

建议站内管理后台至少提供：

- 按租户、用户、功能、provider、模型、日期查看调用量、Token、估算费用、成功率、p50/p95/p99；
- “预算使用率”和“预计月末费用”；
- 失败、重试、fallback、缓存命中的成本拆分；
- 单用户/单租户明细和导出；
- 价格版本变更记录；
- 对异常用户、异常功能、异常模型设置站内告警。

工程侧用 Prometheus + Grafana 看低基数系统指标：

- 全站请求量、错误率、延迟；
- 按 provider/model 的调用量和 token；
- Redis/DB 错误；
- 队列积压和 in-flight；
- 预算服务自身健康度。

质量和会话分析可以用 Langfuse：

- trace、用户会话、prompt 版本、评估和人工标注；
- 需要严格脱敏，且不把 Langfuse 当财务账本。https://langfuse.com/docs/observability/features/users

### 9.4 分阶段优先级

#### P0：先形成可对账的事实

1. 统一 `ChatClient` 构造入口的 observation 和业务 Advisor；
2. 采集 `ChatResponse` usage，写入 `ai_call_events`；
3. 建立价格版本表，先覆盖 OpenAI 兼容和 DashScope 的主力模型；
4. 增加 Redis RPM/TPM/并发闸门；
5. 增加用户/租户日/月软预算告警；
6. 把 Prometheus 指标与账本做每日对账。

验收标准：任何一个用户、功能、模型、日期都能回答“调用多少次、用了多少 token、估算多少钱、成功率和 p95 是多少”。

#### P1：先控制最贵的路径

1. 对试卷识别、整卷生成、长文评估等高价功能启用硬预算和预占；
2. 对普通问答启用低价模型优先或按复杂度路由；
3. 对稳定的系统提示和长资料启用 provider prompt caching；
4. 只对单轮、低变化场景启用精确/语义缓存，并做用户隔离；
5. 记录 fallback 和重试成本，增加“每成功一次回答的平均成本”。

验收标准：单用户/单租户在并发请求下不会明显超过硬预算；缓存节省和 fallback 额外成本可解释。

#### P2：provider 数量和治理复杂度上升后再评估网关

满足以下任一条件时再引入 LiteLLM 或同类网关：

- provider 数超过 3~4 家，手工配置和兼容性测试开始成为主要维护负担；
- 需要统一 fallback、cooldown、成本/延迟路由；
- 需要在网关层统一管理 team/tenant virtual key；
- 愿意承担额外数据库、Redis、服务升级和网络跳点。

LiteLLM 的预算、fallback、Redis 共享限流与虚拟 Key 很契合这些需求，但必须同时部署数据库并明确 fail-closed 策略。https://docs.litellm.ai/docs/proxy/users

#### P3：质量评估和精细运营

- Langfuse Cloud 或独立实例做 trace、prompt 版本、评估和用户会话；
- Grafana 做工程告警，站内报表做产品和财务视图；
- 引入按功能/租户的预算策略模板和自动续期；
- 若未来需要真正的预付余额、退款、发票和外部结算，再在账本上增加 billing 模块。

## 10. 为什么“用户自配 Key”不是必须前提

BYO Key 是一种**成本转移和授权模式**，不是实现多租户观测与配额的技术前提。

- LiteLLM 的 virtual key 可以由管理员创建，用户的请求使用平台发放的 virtual key；real provider key 留在网关配置或环境变量中，用户不需要接触。https://docs.litellm.ai/docs/proxy/virtual_keys
- LiteLLM 的 end-user/customer 预算可以通过请求 `user` 或 customer id 归属，不必给每个最终用户单独发放 provider key。https://docs.litellm.ai/docs/proxy/users
- Helicone 可以用 `Helicone-User-Id` 做用户维度和 per-user 限流，用户仍然使用平台发放的 Helicone key。https://docs.helicone.ai/features/advanced-usage/user-metrics
- Portkey 可以通过 virtual key、workspace 和 `_user` metadata 做组织/用户归因；平台可以持有真实 provider credential，普通用户只使用平台发放的 key 或登录态。https://portkey.ai/docs/product/observability/metadata
- Vercel AI Gateway 也支持平台 API Key/预算和可选的 BYOK；BYOK 支出不计入平台预算，说明 BYOK 只是另一种可选模式，不是观测和预算的前提。https://vercel.com/docs/ai-gateway/observability-and-spend/budgets

因此，本仓库可以继续使用管理员配置的 provider 池：

1. 真实 provider key 只存在服务端 `.env` 或密钥管理系统中；
2. 对外只发平台自己的登录态、内部 virtual key 或租户标识；
3. 在应用层按 `tenant_id/user_id` 记录账本和做预算；
4. 如果以后引入网关，再为每个租户/功能生成 internal virtual key；
5. 用户自配 Key 只作为未来的高级/企业选项，不应成为普通用户的使用门槛。

## 11. 结论摘要

1. **观测标准可以用 OTel GenAI，但财务账本必须自建。** Token usage 有跨供应商规范，费用没有统一 USD 属性；用户、租户、功能、价格版本和最终金额必须由本仓库落账。https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-token-metrics.md

2. **Spring AI 已经能自动提供模型调用耗时和 Token 指标，是本仓库最低成本的观测起点。** 当前文档列出 `gen_ai_client_token_usage_total` 及 input/output/total 标签，也有 `gen_ai.usage.*` trace 属性；但业务功能、用户、重试、fallback 和费用仍需自建。https://docs.spring.io/spring-ai/reference/observability/index.html

3. **控制层不能只靠 Grafana、Langfuse 或采样后的 OTel。** 它们适合发现异常和做分析，但没有调用前的原子预算决策；真正防止成本失控需要在 Redis 中做 RPM/TPM/并发和预算预占，在 PostgreSQL 中保存权威事件，并对失败、重试和 fallback 对账。https://opentelemetry.io/docs/concepts/sampling/

4. **LiteLLM 是当前最值得保留的网关候选，但不是零成本引入。** 它有 virtual key、user/team/end-user budget、预算预占、Redis 多实例限流和 fallback；但预算必须有数据库，DB-less 会 fail open，且需要额外维护 Python 服务、数据库、Redis 和兼容性测试。https://docs.litellm.ai/docs/proxy/users

5. **Hard cap 与 soft cap 要明确区分。** LiteLLM 的预占和 fail-closed 可以支撑硬预算；Vercel 官方则明确其 budget 是 soft cap，跨边界请求仍会完成；Portkey 的 API Key 预算/限流还受 Enterprise/select Pro 套餐限制。https://vercel.com/docs/ai-gateway/observability-and-spend/budgets https://portkey.ai/docs/product/administration/enforce-budget-and-rate-limit

6. **推荐优先级是：P0 自建账本 + Spring AI/OTel 埋点 + Redis 配额；P1 高价功能硬预算、provider prompt caching、cheap-first 和租户隔离缓存；P2 再评估 LiteLLM；P3 再引入 Langfuse 做 trace/eval。** 这套路线继续使用平台自己的 provider key，不需要让普通用户自配 Key。https://docs.spring.io/spring-ai/reference/observability/index.html
