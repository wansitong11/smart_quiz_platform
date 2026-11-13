# RAG 相关性筛选独立测评

这个入口只读开发库中的 admin 可访问且已完成向量化的资料，调用真实改写与 embedding 模型。
不创建或删除数据库，不上传合成资料到 admin 库，不执行消息写入、问题计数或向量化。
合成跨资料用例只写入进程内 SimpleVectorStore。

## 前置条件

- PostgreSQL/pgvector 已运行，`smart-quiz` 库中存在小学数学（当前 ID=4）与 Spring AI（当前 ID=23）。
- 数据与名称、原文锚点不一致时测试失败，不通过挑选新锚点或改 ID 掩盖资料变化。
- `.env` 的数据库连接、`APP_AI_CONFIG_ENCRYPTION_KEY`、数据库中启用的模型 Provider 可用。
- 不需要启动 Spring Boot、前端、Redis 或对象存储；保持 `REDIS_DATABASE=1` 防止与其它测评混用。
- 本测试会发生真实模型费用。通过现有 LlmProviderRegistry 创建模型，用本地 AiCallMeter 记录调用数和聊天 usage；
  不写费用账本以保持只读，费用字段明确标记未定价，不能把它当作零费用。

## PowerShell 运行

在项目根目录执行。Gradle 的 `ragEvaluation` 任务会读取已有 `.env`，无需把凭据复制进命令行。
以下命令只运行这一类，**不要删掉 `--tests`**，否则会连带执行会创建独立数据库的历史测评。

```powershell
$env:RUN_RAG_EVAL = 'true'
$env:REDIS_DATABASE = '1'
$env:RAG_REPLAY_DB_URL = 'jdbc:postgresql://localhost:5432/smart-quiz'
.\gradlew.bat :app:ragEvaluation --tests 'interview.guide.modules.knowledgebase.service.RagRelevanceEvaluationTest' --no-daemon
```

本机中文路径的 Test Worker 如出现编码故障，沿用项目现有的临时 `subst Q:` 路径运行，结束后撤销本次建立的映射；
不要覆盖用户已有 Q 盘。Java 版本必须为 25。

## 输出与判断

每次输出在 `app/build/reports/rag-relevance/<运行时间>/`，该目录被 Git 忽略。

- `dataset.json`：测量前冻结的问题、dev/holdout、历史和证据标签。
- `D*-*-pool.json` / `H*-*-pool.json`：新旧改写及冻结候选池。
- `evaluation.json`：数据/提示词 hash、资料快照、模型、12 组参数逐题结果、四组对照、上下文和本地 usage。
- 报告中的 SELECTED 表示通过 topK 选择；最终是否装入仍看 context.directHits，可能受 6000 token 预算限制。
- 门禁：dev 所有重复均保留必要锚点、排除禁止来源；选择最宽松合格参数后才运行 holdout，不按留出结果调参。
- 同 query、同 topK、同召回阈值的向量候选池在一轮中缓存，新旧选择用相同池；每题三次真实改写不缓存。
- 参数不合格、留出失败、资料缺失或模型/环境错误均失败，不以跳过充当通过。

报告含真实资料正文，不要整体提交到 Git、公开或外传；正式结论经脱敏后写到 `docs/design/`。
即使独立检索测评通过，也还要做正常登录 admin 会话的新消息、出处和回答忠实度验收。
在这些验证完成前，保持 `APP_AI_RAG_RELEVANCE_ENABLED=false` 或不配置；不能只因单测绿就启用。

## 当前实测基线（2026-11-13）

V1 开发题 18/18、留出 9/12；按原先冻结的下一版流程扩充开发题并更换全新留出，
V2 开发题 30/30、留出仍为 9/12。V2 的 H3 技术问题三次均混入数学，已按停止规则停止第三轮自助调参。
当前源码中的题集为 V2，保留失败断言，不降低要求让测评变绿；不要启用选出的 0.35 / 0.15。

V1 输出：`app/build/reports/rag-relevance/20261113-200620-162/`；
V2 输出：`app/build/reports/rag-relevance/20261113-201323-402/`。
两轮在留出断言处停止，后续改写约束专项、真实嵌入合成跨资料和仅命中 35 的真实补邻专项未执行。
完整逐题结论和后续建议见 `docs/design/RAG检索相关性筛选-验证报告.md`。
