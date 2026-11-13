# RAG 测评集（P1-01）

最小可复现的 RAG 基线测评：固定数据集 + 固定配置 + 独立可销毁环境，一条命令产出 JSON/Markdown 报告。

此处的 40 题 v1 是历史检索基线，**不是**章节邻居策略的 48 题验收集，也不能据此启用新策略。报告新增的 `contextText`、直接命中和邻居 ID 记录模型实际使用的上下文；`contextContainsAllEvidence` 仅对有锚点的题目判断所有锚点是否齐全，不用于判断资料外拒答。真实 usage 不可得时仍不得用本地 token 计数冒充。

## 章节连续性对照

`RAG_CONTINUITY_EVAL=true` 使用 `ContinuityEvalCorpus` 固定生成 48 题和三份合成资料，每题运行三次，需分别运行开关关闭和开启的两组；题目当前为 Java 生成，不是设计要求的独立 JSONL/UTF-8 文件。报告包含实际送入模型的上下文及回答；资料外拒答不能用 `predictedReject` 代替人工检查。已执行结果和限制见 `docs/design/RAG语义连续性-验证报告.md`，效果未达验收门槛。

运行前准备真实可用的模型配置、非开发用途的 Redis 库、专用可写 S3/RustFS 桶；运行会产生模型费用并创建、结束时删除独立的 `smart-quiz-rag-eval` 数据库，已有同名库时拒绝覆盖。使用独立存储桶并避免与真实资料共用；不要对开发库或共享对象存储执行清空操作。两组各用空桶、同一 provider 和其余配置，按操作系统设置环境变量后分别运行：

```bash
RUN_RAG_EVAL=true RAG_CONTINUITY_EVAL=true REDIS_DATABASE=14 \
  APP_AI_RAG_SECTION_NEIGHBOR_ENABLED=false ./gradlew :app:ragEvaluation --no-daemon
RUN_RAG_EVAL=true RAG_CONTINUITY_EVAL=true REDIS_DATABASE=14 \
  APP_AI_RAG_SECTION_NEIGHBOR_ENABLED=true ./gradlew :app:ragEvaluation --no-daemon
```

对象存储端点与桶配置使用项目现有的 `APP_STORAGE_*` 环境变量；每次运行前切换到各自的隔离桶。任务使用 `.env` 作为默认值，但显式环境变量优先。报告保存在 `app/build/reports/rag-eval/`，包含测试题及上下文，复制到受控位置前需确认不含真实资料。不要在共享或生产环境运行。

## 运行方式

前置条件：本机 Docker（PostgreSQL + pgvector、Redis）、`.env` 中已配置可用的 Embedding/Chat Provider。

```bash
# 基线（Chunk 800 + 关闭改写，rag-eval Profile 固定）
RUN_RAG_EVAL=true ./gradlew :app:ragEvaluation --no-daemon

# 对照（开启改写，独立 run）
RUN_RAG_EVAL=true APP_AI_RAG_REWRITE_ENABLED=true ./gradlew :app:ragEvaluation --no-daemon
```

- `RUN_RAG_EVAL`：双保险开关之一（另一层是 `rag-eval` 标签，普通 `:app:test` 不会运行本测评）。
- Redis 隔离：`rag-eval` Profile 默认使用 database 1（可用 `REDIS_DATABASE` 覆盖），显式设为 0 会被测评启动守卫拒绝；task 会自动加载根目录 `.env`（含 `APP_AI_CONFIG_ENCRYPTION_KEY` 等）。
- 普通测试命令 `./gradlew :app:test` 通过 `excludeTags 'rag-eval'` 排除本测评，不产生付费调用。

## 环境隔离

- 每次运行创建独立的 PostgreSQL 逻辑库 `smart-quiz-rag-eval`（含 Flyway 迁移），结束后删除；如启动前同名库已存在则拒绝运行，不触碰开发库。
- 测评知识库为库内两条临时 `knowledge_bases` 记录，随库销毁。
- Redis 使用独立 database index，Stream 消息与开发环境互不可见。

## 数据 Schema（dataset-v1.jsonl，40 条）

| 字段 | 说明 |
|---|---|
| `id` | 稳定唯一；前缀对应题型：fact-/para-/ctx-/chunk-/oos- |
| `question` | 用户问题 |
| `history` | 可选多轮上下文 `{role, content}`（仅 ctx- 类使用） |
| `fixture` | 固定知识库文件名（fixtures/ 下），不写运行时 ID |
| `expectedEvidence` | 证据锚点 `[{id, text}]`；片段含任一锚点即命中，多锚点用于覆盖率 |
| `shouldReject` | 知识库外问题标记（oos- 类全部为 true） |
| `tags` | 题型分组统计键 |
| `split` | `dev`（30，调参用）/ `holdout`（10，仅终评） |
| `evaluateGeneration` | 是否执行端到端生成（固定 10 条 dev 站内样本 + 全部 8 条 oos） |

题型分布：12 直接事实 / 8 同义改写 / 6 多轮上下文 / 6 跨段 / 8 知识库外；dev 30 条 / holdout 10 条。语料每篇 fixture 切分后 Chunk 数大于最大 Top K（20），跨段样本锚点经 `RagEvalCorpusTest` 校验确实落在不同 Chunk。

所有内容人工编写，无真实手机号、邮箱、姓名与公司信息。

## 指标定义

- **Hit@K**：前 K 个片段中至少出现一个期望证据锚点的样本占比。
- **EvidenceRecall@K**：命中锚点数 / 该样本全部锚点数（按证据锚点口径，非标准 IR 的 Document Recall；锚点匹配前做大小写、空白与 Markdown 归一化）。
- **MRR**：第一个命中锚点的片段排名倒数均值。
- **拒答 P/R/F1**：oos 样本答案命中拒答模板即判“拒答”，输出混淆矩阵。
- **忠实度**：固定 10 条生成样本人工复核，报告字段 `faithfulnessStatus: PASS|FAIL|UNSURE|PENDING`。
- **耗时**：P50/P95 总耗时、改写、检索、生成分阶段耗时（来自 `RagQueryExecution` 轨迹）。
- **Token**：当前链路使用 `.content()` 无法取得 usage，报告记 null 并标注；补齐属于 P1-04 范围。
- **坏例**：MRR 排名 > 3、EvidenceRecall < 1、oos 未拒答的样本逐条列出。

## 报告

- 原始输出：`app/build/reports/rag-eval/<run-id>.json` 与同名 `.md`（build 目录不入 Git）。
- 正式基线：人工复核并确认脱敏后，复制到本目录 `baselines/<run-id>.json|.md` 入 Git，与 dataset/fixture/Prompt hash、Git SHA、模型标识绑定。

## 复现注意

- 两次 run 可比的前提：同 dataset 版本、同 fixture hash、同 Prompt hash、同 Git SHA、同模型与配置（run 头部完整记录）。
- 新增盲测样本写入 `dataset-v2.jsonl`，不修改 v1。
- 前置环境不可用时任务 FAIL 并产出 `HARNESS_ERROR` 报告，不会把“没跑”误报为“通过”。
