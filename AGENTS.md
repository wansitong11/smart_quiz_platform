 Smart Quiz Platform Agent Rules

Spring Boot 4.1.0 + Java 25 + Spring AI 2.0.1 + React 智能刷题平台。

本文件是跨工具 Agent 入口，只放长期有效、代码里不容易直接推断、猜错会影响结果的规则。更细的目录规则放在 `.claude/rules/`，需要时再读取。

**开工前先读 `docs/改造说明.md`**：项目定位、代码地图、四个核心机制（试卷层级树 / 题型判分 /
资料分类与检索范围 / 问答工具化 + ChatMemory）、本地启动与测试命令、已知坑清单。

**已知待优化事项统一记在 `docs/待优化事项.md`**：动手前先看一眼，别重复踩已知问题、也别把已经登记过的
事项再提一遍；做完一条就把对应条目从那份文件里删掉。

## Tech Stack

- Backend: Spring Boot 4.1.0 / Java 25 / Gradle / Spring AI 2.0.1
- Database: PostgreSQL + pgvector，向量维度 1024，距离类型 COSINE
- Cache & async: Redis / Redisson / Redis Stream
- Storage & parsing: RustFS/S3 / Apache Tika
- Mapping & export: MapStruct / iText 8 / SpringDoc OpenAPI
- Frontend: React 18 / TypeScript / Vite / TailwindCSS 4，代码在 `frontend/`

## Commands

```bash
./gradlew :app:compileJava
./gradlew :app:test --no-daemon
./gradlew :app:bootRun
```

```bash
cd frontend && pnpm run dev
cd frontend && pnpm run build
```

```bash
docker compose -f docker-compose.dev.yml up -d
```

## Project Structure

- `app/src/main/java/interview/guide/common/`: 通用能力，包括限流、AI 调用、异步模板、配置、异常、统一响应。
- `app/src/main/java/interview/guide/infrastructure/`: 技术基础设施，包括文件、导出、Redis、MapStruct 映射。
- `app/src/main/java/interview/guide/modules/`: 业务模块，每个模块自包含 MVC 分层。
- `app/src/main/resources/prompts/`: StringTemplate Prompt 模板。
- `frontend/src/`: React 前端页面、组件、API 客户端和类型定义。

## Architecture

- 后端遵循 `Controller -> Service -> Repository`，Controller 只做路由、校验和委托。
- Service 承担业务编排；`@Transactional` 只放 Service 层，并保持事务范围最小。
- Repository 继承 Spring Data JPA `JpaRepository`，自定义查询用方法名或 `@Query`。
- 基础设施能力优先放在 `common/` 或 `infrastructure/`，不要散落到业务 Service。
- 对外响应统一使用 `Result<T>`，禁止直接返回 Entity。

## Backend Rules

- 业务异常必须使用 `BusinessException(ErrorCode.XXX, "描述信息")`。
- 全局异常处理器返回 HTTP 200 + `Result.error(code, message)`。
- 请求体优先用不可变 `record`，命名后缀使用 `XxxRequest` / `XxxResponse` / `XxxDTO` / `XxxEntity`。
- Entity 到 DTO/Response 的映射优先使用 MapStruct。
- 使用构造器注入，优先配合 Lombok `@RequiredArgsConstructor`。
- 代码使用 2 空格缩进、无通配符导入、避免内联全限定类名。
- 日志使用 SLF4J 占位符，异常必须作为最后一个参数传入。

## AI And Async Rules

- 获取聊天模型统一走 `LlmProviderRegistry.getChatClientOrDefault(provider)`。
- 结构化输出统一走 `StructuredOutputInvoker`，不要在业务代码里复制重试逻辑。
- Prompt 模板放在 `resources/prompts/`，使用 StringTemplate `.st`。
- LLM、S3、外部 HTTP 调用不得放在数据库事务内。
- Redis Stream 生产/消费使用 `AbstractStreamProducer` / `AbstractStreamConsumer` 模板。
- 异步处理前先校验实体是否存在；实体已删除时 ACK 丢弃。
- 限流使用可重复 `@RateLimit`，不要手写散落的 Redis 限流逻辑。

## Config And Data

- 配置集中在 `application.yml`、`.env` 和 `@ConfigurationProperties` 类中。
- API Key、数据库密码等敏感信息只放 `.env`，不得提交到 Git。
- 不要在 Service 中散落 `@Value`。
- 本地默认后端端口是 `8080`：`server.port: ${SERVER_PORT:8080}`。
- 开发环境 `ddl-auto` 可为 `update`，生产环境不能依赖自动建表。

## Frontend Rules

- API 调用集中在 `frontend/src/api/`，复用 `request.ts` 的 Axios 实例。
- 类型定义放在 `frontend/src/types/`，不要在页面里重复定义共享接口。
- 页面放在 `frontend/src/pages/`，可复用 UI 放在 `frontend/src/components/`。
- 路由常量放在 `frontend/src/constants/routes.ts`。
- 组件交互优先使用现有设计语言和 `lucide-react` 图标。

## Testing

- 后端测试使用 JUnit 5 + Mockito + AssertJ。
- 测试意图用中文 `@DisplayName` 描述，复杂场景用 `@Nested` 分组。
- 集成测试使用 H2 配置；限流相关测试需要真实 Redis。
- 改后端公共能力时至少运行 `./gradlew :app:test --no-daemon`。
- 改前端时至少运行 `cd frontend && pnpm run build`。

## 标准测试流程（开发完成后强制）

按本次改动影响面分级，交付前必须清账，不允许带着一堆失败收尾：

- T1 局部（单文件 / 常量 / 文案 / 配置 / 纯样式）：后端 `./gradlew :app:compileJava`、前端 `pnpm build`；受影响类定向测试。
- T2 模块内（改 / 增 Service、Controller、DTO、Repository 方法）：受影响类定向测试 + 同模块关联测试；接口有变再跑前端 `pnpm build`。
- T3 公共 / 跨模块 / 数据 / 并发 / 事务（common、infrastructure、DDL、Redis Stream、AI 调用）：全量 `./gradlew :app:test --no-daemon` + 前端 build（如涉及）。

交付收尾标准：编译通过、对应级别测试全绿；失败必须修复或登记 `docs/待优化事项.md` 并注明原因；
不新增失败 / 跳过；全量回归跑完必须给出失败清单与处置，跑完不看结果等于没测。
详细流程见 `.claude/rules/testing.md`。

## Development Workflow（Codex / DeepSeek）

本仓库开发时，必须遵守下面流程，禁止「能跑就行」和补丁式堆砌。

### 动手前
- 新功能或大改：先对齐需求边界（可用 `$grill-me` / `$grill-with-docs`），写清目标、非目标、失败模式、影响模块。
- 复杂改动：先 `$to-spec` 出接口/分层草图，确认后再 `$implement`；不要边猜边写。
- 每次会话只做一个意图；开新 chat，不要在长线程里叠十次「再改一下」。
- 动手前扫一眼 `docs/待优化事项.md` 和相关 `.claude/rules/`。

### 设计与分层（强制）
- 严格沿用现有模式：`Controller → Service → Repository`；新能力优先扩展现有 Service / 抽象，禁止平行再造一套。
- 跨模块复用放 `common/` 或 `infrastructure/`；业务差异用策略/模板方法扩展（如已有的 Stream Producer/Consumer、`LlmProviderRegistry`、`StructuredOutputInvoker`），不要 if-else 散落。
- 改代码前先说明：影响哪些模块、是否动公共抽象、对事务/异步/AI 调用边界有无副作用。
- 不确定现有约定时先读同目录邻近代码，再提问；禁止凭空引入新框架或新分层命名。

### Bug 修复纪律（强制）

按影响面分级，不搞一刀切：

- L1 局部（单文件/空值/边界/文案/配置）：最小复现 + 一个目标单测 + 定向测试。
- L2 逻辑（跨方法/分支/状态）：失败测试 + 最小修复 + 扫 1 条最相关同类路径。
- L3 核心（公共抽象/事务/异步/并发/数据一致性）：失败测试 + 修复 + 同类路径 + 全量回归。

执行要点：
- 先复现再改；测试基建成本明显高于修复成本时，允许最小复现脚本或手动验证，并说明原因。
- 默认只跑受影响测试：`./gradlew :app:test --tests "完整类名" --no-daemon`；仅 L3 或改公共能力才跑全量。
- Gradle 输出只看尾部：`... 2>&1 | Select-Object -Last 60`；不要整份日志进上下文，失败先看第一个 cause。
- 同类路径只强制扫 1 条最相关的；其余记入 `docs/待优化事项.md`，不展开。
- 同一 bug 连续 2 次修复失败，停下来汇报根因假设与下一步，不再盲试。
- 临时 workaround 不得进主分支；必须暂存时标 `TODO` + 拆除条件。
- `$diagnosing-bugs` / `$tdd` / `$bugfix` 只用于 L2/L3 或疑难 bug，L1 直接按本节走。

Bug 解释（强制）

主说明按「开发对产品经理」的方式讲清「发生了什么 → 为什么错 → 改成什么」，默认大白话；
不加专业术语会影响理解时，可适当使用（代码标识符可少量点名当路标）。
实现细节（根因定位 / 修复方式 / 影响面 / 验证）单独给、按需给，不混在主说明里。
完整 diff、失败日志、全量回归结果落盘文档，只给摘要 + 路径。

### 会话提示习惯
- 用户/开发者应用明确范围开场，例如：「只做 X；不动 Y；沿用现有 Z 模式」。
- Agent 回复必须先给方案影响面，再改代码；禁止 silent drive-by 大范围重写。


## 设计说明交付（强制）

所有「设计 / 方案 / 新能力说明」按 `.claude/rules/design-delivery.md` 交付：

- 正文按「一个开发向另一个开发讲逻辑」的方式写：目的 / 触发 / 规则与步骤 / 结果 / 边界与异常 / 可改点；关键步骤顺带讲清大致怎么实现。
- 正文不要通篇堆项目内部英文类名、方法名、字段名和错误码；必要处可加项目内代码标识符作辅助，点一下并说明它在流程里做什么；完整命名/签名/分支放附录。
- 判断标准：熟悉业务但没看过这段代码的开发，读完正文能复述逻辑、说出大致怎么实现；要记住一堆英文名才能看懂，就是写偏了。
- AI 可照做的实现表述（入口签名 / 数据 / 步骤映射 / 状态分支 / 可改点映射）单独放「附录」，必须自足到零猜测：只读附录就能完整实现、不用补决策。
- 附录不能只贴代码：每个模块/方法先用几句话讲职责、输入输出、执行顺序、关键状态和失败路径；代码对不易一眼看出的分支、并发、条件更新和异常路径加简短注释，不逐行解释样板代码。

验收标准：正文让没看过代码的人能看懂逻辑、知道怎么改，关键逻辑的实现方式已融进描述、开发不用先翻附录；附录让模型只读附录即可完整实现、零猜测。附录不全就不进实现。
对话内只给正文骨架 + 摘要，完整版（正文 + 附录）落盘 `docs/design/`。
交付分 2–3 轮，每轮用户确认；实现前用一句话复述意图，用户点头才动手。


## Never Do
- 不要 `throw new RuntimeException(...)`，业务失败必须用 `BusinessException`。
- 不要直接返回 Entity 给前端。
- 不要把 `@Value` 散落在 Service 中。
- 不要在事务内调用 LLM、S3 或外部 HTTP。
- 不要同类内部调用 `@Transactional` 方法。
- 不要 `catch (Exception e) {}` 静默忽略。
- 不要循环调用 DB，优先批量查询或批量写入。
- 不要硬编码密钥、Token、数据库密码。
- 不要使用 `Executors.newXxxThreadPool()`，需要线程池时显式配置 `ThreadPoolExecutor`。
- 不要在没有复现的情况下直接改实现来「碰运气」修 bug。
- 不要为同一问题连续打补丁而不回溯根因与同类路径。
- 不要引入与现有 Controller/Service/Repository 平行的第二套分层或命名。
## More Rules

- 后端 Java 细则：`.claude/rules/backend.md`
- AI、限流、异步细则：`.claude/rules/ai-and-async.md`
- 前端细则：`.claude/rules/frontend.md`
- 设计说明交付规范：`.claude/rules/design-delivery.md`
- Bug 修复说明规范：`.claude/rules/bugfix.md`
- 标准测试流程：`.claude/rules/testing.md`
