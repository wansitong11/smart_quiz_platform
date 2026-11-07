# 通用有界执行器统一散落的线程池

项目里线程池创建方式不一致：`DocumentParseConfiguration` 手写了一份有界
`ThreadPoolExecutor`，另有 `InterviewQuestionService`、`VoiceInterviewWebSocketHandler`、
`RagQueryTraceService` 三处直接 `Executors.newVirtualThreadPerTaskExecutor()`，违反 AGENTS.md
「禁止 `Executors.newXxxThreadPool()`」的约定；而纸卷识别的高并发改造需要一个可背压、可观测的
页级执行器。决定：在 `common/async` 提供唯一构建逻辑（`BoundedExecutorFactory` 产出
`BoundedExecutor`），每个功能用 typed `@ConfigurationProperties` 声明自己的池参数（线程数、队列
容量、拒绝策略、是否传播 SecurityContext/MDC），默认传播上下文并保留关闭开关，内置 Micrometer
指标。`document-parse` 迁移为第一个使用方，纸卷识别作为第一个新增使用方。

## Considered Options

- 纯配置注册 `app.executors.<name>.*` 自动成 bean：新增功能零代码，但键是魔法字符串，校验和
  默认值难做，出问题不易排查。
- 全面改用 virtual thread：对「provider 有并发配额」的视觉调用无法背压，会打满上游。
- 继续每个功能手写 `ThreadPoolExecutor`：现状，样板漂移、线程命名/指标/上下文传播各写各的。

## Consequences

- 新增功能并发只需一份 typed 配置 + 几行 `@Bean`，构建、指标、传播、关闭逻辑只写一次。
- 三处 virtual-thread 用法本轮不迁移，登记到 `docs/待优化事项.md`，后续单独收敛。
- 未来主入口高并发复用同一个 `BoundedExecutor`，不用再改通用工具。
