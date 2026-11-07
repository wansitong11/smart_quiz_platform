# 标准测试流程（testing）

适用：任何开发完成后到交付前。
目的：按改动影响面跑对应级别的测试，避免「开发完测试还有一堆错误」。

## 分级

- T1 局部（单文件 / 常量 / 文案 / 配置 / 纯样式）
  - 后端：`./gradlew :app:compileJava`
  - 前端：`cd frontend && pnpm run build`
  - 受影响类的定向测试：`./gradlew :app:test --tests "完整类名" --no-daemon`
- T2 模块内（改 / 增 Service、Controller、DTO、Repository 方法）
  - T1 的内容 + 同模块关联测试（相邻类、映射、校验）
  - 接口 / 类型有变化时跑前端 `pnpm build`
- T3 公共 / 跨模块 / 数据 / 并发 / 事务（common、infrastructure、DDL、Redis Stream、AI 调用、限流）
  - 全量：`./gradlew :app:test --no-daemon`
  - 涉及前端时 `cd frontend && pnpm run build`
  - DDL / 迁移：确认 Flyway 迁移文件与集成测试通过

## 执行顺序

编译 → 定向测试 → 按级别回归 → 汇总失败清单 → 逐条清账。

## 交付收尾标准

- 编译通过，无新增编译错误。
- 对应级别测试全绿；任何失败必须修复，或登记 `docs/待优化事项.md` 并注明原因。
- 不新增失败 / 跳过；已有失败不被本次改动扩散。
- 全量回归跑完必须给出失败清单与处置（修 / 已知不相关 / 跳过原因）；跑完不看结果等于没测。
- 测试输出只看尾部（`... 2>&1 | Select-Object -Last 60`），失败逐条定位第一个 cause，不把整堆错误带进上下文。

## 判定改动级别

不确定级别时取更严一级（宁可多跑）。涉及公共抽象 / 事务 / 异步 / AI / 数据迁移的，一律按 T3。
