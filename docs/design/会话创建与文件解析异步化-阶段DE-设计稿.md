# 会话创建与文件解析异步化（D/E 阶段）- 设计稿（第二版）

> 状态更新（2026-11-02）：本文保留 D/E 阶段的历史实现记录。其中
> `questionGenExecutor`、`paperInputExecutor` 以及“消费者立即 ACK”的旧外层并发模型，
> 已被 [`redis-stream-并发消费改造-设计稿.md`](redis-stream-并发消费改造-设计稿.md)
> 取代；业务内部执行器（如 `questionInnerExecutor`）仍然有效。
>
> 用途：审批用设计稿。承接 `试卷上传统一与高并发改造-设计稿.md` 的阶段 D / E。
> 状态：已实施（2026-11-01）。后端编译、D/E 定向测试、前端类型检查与 Vite 构建通过；
> 全量回归仍有已登记在 `docs/待优化事项.md` §3.1 的知识库旧用例失败。
> 前置事实：阶段 A/B/C 已落地（统一上传入口、报告只读化、per-session 锁、大文件拆分）。
> 本稿只覆盖两条高并发深水区：会话创建异步化（D）、文件解析下沉到异步输入处理（E）。
> 相对第一版的核心修订：消费者改为「领取即投递、worker 自行落终态」，新增通用
> `dispatchAsync` 模板钩子；D 新增「出题请求快照」持久化；附录补齐到零猜测级别。

## 0. 结论摘要

D 和 E 都遵循同一条原则：**请求线程只做「校验 + 存原料 + 建壳记录 + 投递」，重活交给
Redis Stream 消费者；消费者单线程只负责领取与投递，真正的重活由各功能自己的有界执行器
并行执行，worker 结束后自行落完成 / 失败 / 退避重试。**

- D：创建面试会话时不再在请求线程生成题目，而是同步建一条「无题目的壳会话」并持久化出题
  入参快照，投递出题任务后立刻返回；前端轮询直到题目就绪。
- E：上传试卷时不再在请求线程解析 PDF / DOCX / Tika 与 OCR 分类，而是同步存原始文件、
  建试卷壳记录，投递「输入处理」任务；消费者解析、建页、再决定走直切还是识图。
- 两个阶段都复用现有 Stream 领取 / 心跳 / 恢复范式与 `BoundedExecutor` 通用线程池，
  不新造第二套并发框架；唯一的公共抽象改动是给 `AbstractStreamConsumer` 增加一个
  「异步投递」钩子（见 1.1 / 6.0）。

## 1. 现状审查（影响 D/E 的代码与框架结论）

### 1.1 通用消费者是单线程消费，重任务不能在消费线程里串行做

`common/async/AbstractStreamConsumer.java` 的线程池 core=max=1，队列无界；`processMessage`
在调用 `processBusiness(payload)` 返回前，不会领取下一条消息。现有「切题」「识别」消费者
虽然内部用 `BoundedExecutor` 做页级并行，但 `processBusiness` 仍然是**同步阻塞**到整份任务
结束，所以跨试卷 / 跨会话的任务是被串行消费的——这是现有链路可接受、但 D/E 高并发不能接受
的语义。

结论：D/E 的两个新 Stream 必须走「**领取 → 投递到有界执行器 → 消费者立刻 ACK 并领取下一条
→ worker 自行写完成 / 失败 / 重试**」。因此给 `AbstractStreamConsumer` 增加一个布尔钩子
`dispatchAsync(T payload)`（默认 `false`，保持现有同步语义不变）；返回 `true` 时，模板在
`processBusiness` 返回后直接 ACK，不再调用 `markCompleted`。worker 通过新增的
`completeAsyncTask(payload, retryCount, error)` 复用与同步模式完全一致的失败 / 退避策略。
多线程消费本身仍然不做（会破坏 `tryMarkProcessing` 条件领取与心跳所有权语义）。

### 1.2 `BoundedExecutor` 已就绪，但出题服务还在用无界 virtual thread

`common/async/BoundedExecutorFactory` 已支持「每个功能一个命名池 + 自己的
`@ConfigurationProperties`（poolSize / queueCapacity / rejectionPolicy）」；
`PaperPageRecognitionProcessor`、`DocumentParseService` 已在用。但
`InterviewQuestionService` 构造函数里仍是 `Executors.newVirtualThreadPerTaskExecutor()`，
违反仓库规则且无界，高并发下会无上限拉 LLM。

结论：D 必须把出题内部并行执行器换成注入的 `BoundedExecutor`；同时新增一个「会话级」外层
有界执行器用于控制并发出题会话数（两池职责见 6.1.8）。E 新增的输入解析执行器也走同一工厂。

### 1.3 统一上传路径没有持久化原始文件

`PaperRecognitionService.doUpload` 现在是 `readBytes → parse(内存) → createPages(只存页图)`，
**原始 PDF / DOCX / 图片从未落 RustFS**。若把解析搬到异步，消费者拿不到原料。

结论：E 必须先在请求线程把原始字节 `uploadBytes(..., "paper-input")` 到 RustFS，并新增
`paper_input_files` 表保存「一份试卷对应哪些输入文件（按 seq 保序）」。

### 1.4 领取 / 心跳 / 恢复已有一套成熟范式，直接复用

`PENDING → PROCESSING` 条件领取（`tryMarkProcessing`）、`attemptId` 心跳、恢复调度器按
`updatedAt` 重置卡死任务并限量补投，这套在切题 / 识别 / 评估链路上已经验证。

结论：D 给 `practice_sessions` 加一套出题状态列，E 给 `practice_papers` 加一套输入处理状态列，
再各配一个恢复调度器即可，不引入新的任务框架。

### 1.5 会话创建幂等键已存在，但结果缓存时机需要提前

`InterviewSessionService.createIdempotentSession` 现在要等题目生成并落库后才写
`interview:create:result:{requestId}`。异步化后题目不在请求线程生成，若仍等题目，重复请求会
在锁里再建一份。

结论：D 必须在壳会话落库后**立即**把 `sessionId` 写入幂等结果缓存；重复请求直接返回壳会话与
当前出题状态。

### 1.6 前端仍按「创建即拿到题目」假设

`InterviewPage.startInterview` 调 `createSession` 后立刻 `initSession`，且
`frontend/src/api/interview.ts` 给创建请求设了 180 秒超时；`InterviewSessionDTO` 也没有
「出题中」状态。E 的上传侧同样假设上传返回就知道 `processingPath` 和页数。

结论：D/E 都要改前端为「创建 / 上传立即返回 → 轮询状态 → 就绪后进入下一阶段」。

### 1.7 配额与路径判定依赖解析结果，必须后移到异步阶段

`chargePageQuota` 只对视觉页计费、`processingPath` 由是否含 IMAGE 页决定，这两者都要等解析
完成才知道。请求线程只能先做文件级校验（白名单 / 大小 / 哈希去重），页级配额、`maxVisionPages`、
`maxTextChars` 与路径判定放到输入处理 worker 里。

## 2. 目标与非目标

### 2.1 目标

- 请求线程不再执行 LLM 出题（D）与 PDF / DOCX / Tika 解析、OCR 分类（E）；
- 两条链路都具备：幂等、条件领取、心跳、失败可读、恢复重投、有界并发与观测；
- 对外契约保持向后兼容：旧前端拿到壳会话 / 输入处理中状态也能正确等待；
- 失败原因与重试入口可被用户感知，不静默丢失。

### 2.2 非目标

- 不改 Redis Stream 消费骨架为「多线程消费」（仍单消费者 + 内部有界执行器）；
- 不改 `paper_assets` 资源归属与视觉识别稳定性设计；
- 不引入新的 vision provider、不换任务队列；
- 不做多租户跨进程限流（视觉 provider 全局配额仍按 `docs/待优化事项.md` 单独处理）；
- 不把 `createSessionFromQuestions`（试卷 / 知识库出题结果建会话）异步化——它没有 LLM，已经是
  异步切题链路里的一步；
- 不把上传改为流式写入存储（原始字节仍先 `readBytes` 进内存，受 `MAX_FILES=10` 与
  50MB/文件 上限约束；流式直写列为后续优化）。

## 3. D：会话创建异步化（正文）

### 3.1 目的

让「创建面试」接口秒回，题目生成在后台进行，避免几十秒到分钟级的 LLM 出题占用 Web 请求线程。

### 3.2 触发

用户点「开始面试」，前端调 `POST /api/interview/sessions` 创建会话时。

### 3.3 规则与步骤

1. 请求线程校验请求，保留现有幂等键 / 未完成会话判断；
2. 生成 `sessionId`，把本次出题入参（简历文本、方向、难度、题量、自定义分类、JD、LLM provider）
   序列化成「出题请求快照」一并落库，**不在此刻查询历史题、不调 LLM**；
3. 落一条「壳会话」：`paper_json=空树`、`totalQuestions=0`、出题状态为「排队中」；
4. 立即写 Redis 缓存与幂等结果缓存，投递出题任务；
5. 返回壳会话与「出题中」状态；
6. 后台消费者领取任务 → 置「处理中」→ 投递到会话级有界执行器 → worker 读取快照、重查历史题、
   调现有 `generateQuestionsBySkill` → 成功后写回 `paper_json` / `totalQuestions`，
   置「完成」并刷新缓存；
7. 前端轮询会话，直到「完成」才进入答题页；失败则展示可读原因和「重新出题」。

### 3.4 结果

- 创建接口平均耗时降到毫秒级；
- 会话状态、题目与历史作答仍以 `practice_sessions` + `paper_json` 为唯一事实源；
- 幂等请求 / 页面刷新不会重复出题。

### 3.5 边界与异常

- 出题中重复点「开始」：返回同一个壳会话，不重复投递；
- 出题失败：保留壳会话，出题状态为「失败」，用户可点「重新出题」；
- 卡死：恢复调度器按更新时间把「排队 / 处理中」超时的任务重置并限量补投；
- 历史题查询失败：与现状一致，按业务异常失败；
- 无简历 + 方向题全失败：沿用现有「通用兜底题」降级，不算失败；
- 用户在题目未就绪时访问答题页：前端只显示「题目生成中」，禁止提交答案；
- 队列满（`ExecutorRejectedException`）：壳会话标「失败」，原因「系统繁忙，请稍后重试」，
  用户可手动重试。

### 3.6 可改点

- 出题并发会话数与队列：`interview.question-gen.executor`；
- 出题内部并行（简历题 / 方向题）线程数：`interview.question-inner.executor`；
- 出题恢复超时与补投上限：`interview.question-gen.recovery`；
- 前端轮询间隔：`InterviewPage` 的轮询常量。

## 4. E：文件解析下沉（正文）

### 4.1 目的

让「上传试卷」接口秒回，PDF 渲染、DOCX / Tika 解析、OCR 分类、页资源裁切这类重活放到后台
输入处理任务里做。

### 4.2 触发

用户上传试卷 / 练习材料，前端调 `POST /api/papers/upload` 时。

### 4.3 规则与步骤

1. 请求线程只做：文件白名单与大小校验、读字节、探测 MIME、算 SHA-256、按 `(owner, hash)` 去重；
2. 原始字节落 RustFS（`paper-input` 前缀），保存「该试卷由哪些输入文件组成」的持久化记录；
3. 建一份「输入处理中」的试卷壳记录，立即返回 `paperId + 输入处理中`；
4. 后台输入处理消费者：下载原始文件 → 投递到输入解析有界执行器 → worker 解析 → 建页 / 存页图 /
   写资源 → 统计视觉页与文字页，并在此刻才做 `maxVisionPages` / `maxTextChars` 校验与配额扣减；
5. 按解析结果分流：含图片页 → 扣识别页配额并投递识别任务；纯文字 → 合并正文并投递切题任务；
6. 输入处理完成后，前端轮询到「完成」再决定去识别校对页还是切题进度页；
7. 解析失败：置「失败」并保留原始文件与失败原因，用户可重试或重传。

### 4.4 结果

- 上传接口耗时降到毫秒级；
- 原始文件持久化，解析任务可重试、可恢复，不再因请求超时丢卷；
- 页级配额与 `processingPath` 的判定时机后移，但对外状态语义不变。

### 4.5 边界与异常

- 解析失败：原始文件保留，输入处理状态「失败」，不落脏页 / 脏资源；
- 卡死：输入处理恢复调度器按更新时间重置超时任务并限量补投；
- 配额不足：在输入处理阶段才判定，失败信息与现状一致；
- 去重在请求线程完成，不因异步而重复建卷；
- 混合多文件：输入文件表按 `seq` 保序，解析顺序与上传顺序一致；
- 队列满：输入处理状态「失败」，原因「系统繁忙，请稍后重试」。

### 4.6 可改点

- 输入解析线程数与队列：`paper.input.executor`；
- 输入处理恢复超时与补投上限：`paper.input.recovery`；
- 原始文件保留策略：是否解析成功后删除原始件，可加配置开关。

## 5. 实施顺序与风险

建议先 D 后 E：

1. D 影响面试主链路，但改动面集中在 `InterviewSessionService` / 前端 `InterviewPage`，风险可控；
2. E 影响上传主链路和已有识别 / 切题分流，需要先把 `doUpload` 里只在请求线程使用的
   `createPages / enqueueDirectExtraction / chargePageQuota / findParser / resolveSourceType`
   抽到 `PaperInputProcessingService`，再插入输入处理 Stream，风险更高，单独一轮评审。

两个阶段都不触碰当前未提交的脏改动（资源归属、视觉稳定性、错题本等）。

## 6. 附录（实现层，零猜测）

### 6.0 公共模板改动（`common/async/AbstractStreamConsumer`）

1. 新增钩子（默认 `false`，现有消费者行为不变）：

```java
/**
 * 返回 true 表示 processBusiness 已把任务投递到有界执行器并交由 worker 自行落终态；
 * 模板立即 ACK 并继续领取下一条，不再调用 markCompleted。
 */
protected boolean dispatchAsync(T payload) {
    return false;
}
```

2. `processMessage` 成功分支改为：

```java
processBusiness(payload);
if (dispatchAsync(payload)) {
    ackMessage(messageId);
    return;
}
markCompleted(payload);
ackMessage(messageId);
```

3. 把现有 catch 里的失败策略抽成私有方法，并新增 worker 回调复用同一策略：

```java
protected final void completeAsyncTask(T payload, int retryCount, Exception error) {
    if (error == null) {
        markCompleted(payload);
        return;
    }
    handleTaskFailure(payload, retryCount, error);
}

private void handleTaskFailure(T payload, int retryCount, Exception error) {
    logTaskFailure(payload, error);
    if (error instanceof BusinessException business) {
        markFailed(payload, truncateError(describeFailure(business)));
        return;
    }
    if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
        retryMessage(payload, retryCount + 1);
    } else {
        markFailed(payload, truncateError(
            taskDisplayName() + " failed after retry " + retryCount + "：" + describeFailure(error)));
    }
}
```

catch 块改为 `handleTaskFailure(payload, retryCount, e); ackMessage(messageId);`，行为与现状
完全一致（只是抽出复用）。**不改** `ExecutorRejectedException` 在模板里的默认走向；D/E 消费者
在提交时自行捕获它并转成 `BusinessException(ErrorCode.SYSTEM_BUSY, "系统繁忙，请稍后重试")`，
从而走「确定性失败 → markFailed」分支。

4. 新增错误码 `ErrorCode.SYSTEM_BUSY`（建议 `3007`，文案「系统繁忙，请稍后重试」），供 D/E
  提交被拒时使用。

### 6.1 D 后端

#### 6.1.1 Flyway DDL（如 `V20261101__session_question_gen.sql`）

```sql
ALTER TABLE practice_sessions
  ADD COLUMN question_status varchar(20) NOT NULL DEFAULT 'COMPLETED',
  ADD COLUMN question_error varchar(500),
  ADD COLUMN question_attempt_id varchar(36),
  ADD COLUMN question_updated_at timestamp(6),
  ADD COLUMN question_recovery_count integer NOT NULL DEFAULT 0,
  ADD COLUMN question_gen_request_json text;
```

`question_status` 默认 `COMPLETED`：历史会话无需迁移即可读作「已完成」。新壳会话显式置 `PENDING`。

#### 6.1.2 实体字段（`PracticeSessionEntity`）

```java
@Enumerated(EnumType.STRING)
@Column(name = "question_status", length = 20)
private AsyncTaskStatus questionStatus = AsyncTaskStatus.COMPLETED;

@Column(name = "question_error", length = 500)
private String questionError;

@Column(name = "question_attempt_id", length = 36)
private String questionAttemptId;

@Column(name = "question_updated_at")
private LocalDateTime questionUpdatedAt;

@Column(name = "question_recovery_count", nullable = false)
private int questionRecoveryCount = 0;

@Column(name = "question_gen_request_json", columnDefinition = "TEXT")
private String questionGenRequestJson;
```

#### 6.1.3 出题请求快照（新 record，`modules/interview/model`）

```java
public record QuestionGenRequestSnapshot(
    String resumeText,
    Long resumeId,
    String skillId,
    String difficulty,
    int questionCount,
    List<InterviewSkillService.CategoryDTO> customCategories,
    String jdText,
    String llmProvider
) {}
```

壳会话落库时用 `ObjectMapper.writeValueAsString(snapshot)` 写入 `question_gen_request_json`；
worker 用 `objectMapper.readValue(json, QuestionGenRequestSnapshot.class)` 反序列化。
`CategoryDTO` 已随 `CreateInterviewRequest` 被 Jackson 序列化 / 反序列化，可安全复用。

#### 6.1.4 Stream 常量（`AsyncTaskStreamConstants` 追加）

```java
public static final String INTERVIEW_QUESTION_GEN_STREAM_KEY = "interview:question-gen:stream";
public static final String INTERVIEW_QUESTION_GEN_GROUP_NAME = "interview-question-gen-group";
public static final String INTERVIEW_QUESTION_GEN_CONSUMER_PREFIX = "interview-question-gen-consumer-";
```

消息字段复用 `FIELD_SESSION_ID = "sessionId"` 与 `FIELD_RETRY_COUNT = "retryCount"`。

#### 6.1.5 Repository 方法（`InterviewSessionRepository` 追加，风格对齐 `ResumeRepository`）

```java
@Transactional
@Modifying
@Query("UPDATE PracticeSessionEntity s SET s.questionStatus = 'PROCESSING', "
    + "s.questionAttemptId = :attemptId, s.questionUpdatedAt = :now "
    + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PENDING'")
int tryMarkQuestionGenProcessing(@Param("sessionId") String sessionId,
                                 @Param("attemptId") String attemptId,
                                 @Param("now") LocalDateTime now);

@Transactional
@Modifying
@Query("UPDATE PracticeSessionEntity s SET s.questionUpdatedAt = :now "
    + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PROCESSING' "
    + "AND s.questionAttemptId = :attemptId")
int heartbeatQuestionGenProcessing(@Param("sessionId") String sessionId,
                                   @Param("attemptId") String attemptId,
                                   @Param("now") LocalDateTime now);

@Transactional
@Modifying
@Query("UPDATE PracticeSessionEntity s SET s.paperJson = :paperJson, "
    + "s.totalQuestions = :totalQuestions, s.questionStatus = 'COMPLETED', "
    + "s.questionError = null, s.questionAttemptId = null, s.questionUpdatedAt = :now "
    + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PROCESSING' "
    + "AND s.questionAttemptId = :attemptId")
int completeQuestionGenIfProcessing(@Param("sessionId") String sessionId,
                                    @Param("attemptId") String attemptId,
                                    @Param("paperJson") String paperJson,
                                    @Param("totalQuestions") int totalQuestions,
                                    @Param("now") LocalDateTime now);

@Transactional
@Modifying
@Query("UPDATE PracticeSessionEntity s SET s.questionStatus = 'FAILED', "
    + "s.questionError = :error, s.questionAttemptId = null, s.questionUpdatedAt = :now "
    + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PROCESSING' "
    + "AND s.questionAttemptId = :attemptId")
int failQuestionGenIfProcessing(@Param("sessionId") String sessionId,
                                @Param("attemptId") String attemptId,
                                @Param("error") String error,
                                @Param("now") LocalDateTime now);

@Transactional
@Modifying
@Query("UPDATE PracticeSessionEntity s SET s.questionStatus = 'FAILED', "
    + "s.questionError = :error, s.questionAttemptId = null, s.questionUpdatedAt = :now "
    + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PENDING'")
int failQuestionGenIfPending(@Param("sessionId") String sessionId,
                             @Param("error") String error,
                             @Param("now") LocalDateTime now);

@Transactional
@Modifying
@Query("UPDATE PracticeSessionEntity s SET s.questionStatus = 'PENDING', "
    + "s.questionAttemptId = null, s.questionUpdatedAt = :now "
    + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PROCESSING' "
    + "AND s.questionAttemptId = :attemptId")
int resetQuestionGenToPending(@Param("sessionId") String sessionId,
                              @Param("attemptId") String attemptId,
                              @Param("now") LocalDateTime now);

@Transactional
@Modifying
@Query("UPDATE PracticeSessionEntity s SET s.questionRecoveryCount = "
    + "s.questionRecoveryCount + 1 WHERE s.sessionId = :sessionId")
int incrementQuestionGenRecoveryCount(@Param("sessionId") String sessionId);

@Transactional
@Modifying
@Query("UPDATE PracticeSessionEntity s SET s.questionRecoveryCount = 0 "
    + "WHERE s.sessionId = :sessionId")
int resetQuestionGenRecoveryCount(@Param("sessionId") String sessionId);

@Query("SELECT s FROM PracticeSessionEntity s WHERE s.questionStatus = :status "
    + "AND s.questionUpdatedAt < :threshold ORDER BY s.questionUpdatedAt ASC")
List<PracticeSessionEntity> findStaleByQuestionStatus(
    @Param("status") AsyncTaskStatus status,
    @Param("threshold") LocalDateTime threshold,
    Pageable pageable);
```

恢复调度器对 stale `PROCESSING` 的「重置回 PENDING」直接复用
`resetQuestionGenToPending(sessionId, attemptId, now)`（attemptId 从实体 `questionAttemptId`
读取，先 `findStaleByQuestionStatus` 拿到实体再逐个重置）。

#### 6.1.6 Producer / Consumer（`modules/interview/listener`）

`InterviewQuestionGenStreamProducer extends AbstractStreamProducer<QuestionGenPayload>`：

- `buildMessage` → `Map.of(FIELD_SESSION_ID, sessionId, FIELD_RETRY_COUNT, "0")`；
- `sendQuestionGenTask(String sessionId)` 包装 `sendTask`；
- `onSendFailed` → `sessionRepository.failQuestionGenIfPending(sessionId, "任务入队失败", now)`。

`InterviewQuestionGenStreamConsumer extends AbstractStreamConsumer<QuestionGenPayload>`：

- payload 含 `sessionId`、`attemptId`、`retryCount`（`parsePayload` 从消息读
  `FIELD_SESSION_ID` 与 `FIELD_RETRY_COUNT`）；
- `shouldSkip` → 会话不存在，或 `questionStatus == COMPLETED`；
- `tryMarkProcessing` → `tryMarkQuestionGenProcessing(...) == 1` 才置 `attemptId`；
- `dispatchAsync` → `return true`；
- `processBusiness`：

```java
try {
    questionGenExecutor.execute(() -> {
        try {
            sessionQuestionGenerationService.generate(payload.sessionId(), payload.attemptId());
            completeAsyncTask(payload, payload.retryCount(), null);
        } catch (Exception e) {
            completeAsyncTask(payload, payload.retryCount(), e);
        }
    });
} catch (ExecutorRejectedException e) {
    throw new BusinessException(ErrorCode.SYSTEM_BUSY, "系统繁忙，请稍后重试");
}
```

- `markCompleted` / `markFailed` / `retryMessage`：直接委托 6.1.5 的
  `completeQuestionGenIfProcessing / failQuestionGenIfProcessing / resetQuestionGenToPending`
  并重投；`retryMessage` 与现有识别消费者同构（reset 成功才 `streamAdd`，失败则 ACK 丢弃）。

**关键实现约定（避免终态竞态）**：`SessionQuestionGenerationService.generate` 只负责计算题目，
最终「写 paperJson / totalQuestions 并推进 COMPLETED」统一由 `completeQuestionGenIfProcessing`
完成，该更新以 `attemptId + PROCESSING` 为前提；worker 随后 `completeAsyncTask(..., null)` 再调
`markCompleted` 时因 `attemptId` 已清空而 no-op，形成双保险。

#### 6.1.7 `SessionQuestionGenerationService`（新，`modules/interview/service`）

```java
public void generate(String sessionId, String attemptId) {
    PracticeSessionEntity entity = sessionRepository.findBySessionId(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "答题记录不存在"));
    QuestionGenRequestSnapshot snapshot = readSnapshot(entity.getQuestionGenRequestJson());
    heartbeat(attemptId);
    List<HistoricalQuestion> history =
        persistenceService.getHistoricalQuestions(snapshot.skillId(), snapshot.resumeId());
    List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
        snapshot.llmProvider(), snapshot.skillId(), snapshot.difficulty(),
        snapshot.resumeText(), snapshot.questionCount(), history,
        snapshot.customCategories(), snapshot.jdText());
    heartbeat(attemptId);
    PaperTree tree = PaperTree.fromFlatQuestions(questions, snapshot.skillId());
    int updated = sessionRepository.completeQuestionGenIfProcessing(
        sessionId, attemptId, tree.toJson(objectMapper), questions.size(), LocalDateTime.now());
    if (updated == 1) {
        sessionCache.saveSession(sessionId,
            snapshot.resumeText() != null ? snapshot.resumeText() : "",
            snapshot.resumeId(), null, null, tree, 0, SessionStatus.CREATED);
    }
}
```

`readSnapshot`：`questionGenRequestJson` 为空时抛
`BusinessException(ErrorCode.INTERNAL_ERROR, "出题请求快照缺失")`。
`heartbeat`：调 `heartbeatQuestionGenProcessing`，返回 0 时抛
`BusinessException(ErrorCode.INTERNAL_ERROR, "出题任务执行权已失效")`。

#### 6.1.8 `InterviewQuestionService` 改造（两池，去掉无界 virtual thread）

- 删除 `Executors.newVirtualThreadPerTaskExecutor()` 与 `@PreDestroy destroy()`；
- 构造函数注入 `@Qualifier("questionInnerExecutor") BoundedExecutor questionExecutor`，
  内部简历题 / 方向题的 `CompletableFuture.supplyAsync(..., questionExecutor)` 保持不变；
- 新增配置类 `InterviewQuestionExecutorConfiguration` 产出两个 bean：
  - `questionGenExecutor`（会话级外层池，供消费者投递 `SessionQuestionGenerationService`）：
    `factory.create("interview-question-gen", questionGenExecutorProperties)`，
    默认 `poolSize=2、queueCapacity=20、ABORT`；
  - `questionInnerExecutor`（会话内部简历/方向并行池）：
    `factory.create("interview-question-inner", questionInnerExecutorProperties)`，
    默认 `poolSize=4、queueCapacity=20、ABORT`。
- 属性类 `InterviewQuestionGenExecutorProperties` / `InterviewQuestionInnerExecutorProperties`
  均 `extends BoundedExecutorProperties`，前缀分别为 `interview.question-gen.executor` 与
  `interview.question-inner.executor`。

说明：外层池控制「同时生成几份会话」，内层池控制「一份会话内部最多并行几个 LLM 子调用」。
若用同一个池，外层 worker 占线程等待内层子任务，poolSize=2 时会出现排队互相等，故拆成两池。

#### 6.1.9 `InterviewSessionService` 改造

`createSessionInternal(CreateInterviewRequest, String requestId)`：

1. 保留「未完成会话」判断；
2. `sessionId = UUID...substring(0,16)`；`skillId / difficulty` 默认值逻辑不变；
3. 构建 `QuestionGenRequestSnapshot`（不含 `requestId/forceCreate`）并序列化；
4. 调用新方法 `persistenceService.saveQuestionShell(sessionId, resumeId, requestId,
   llmProvider, skillId, difficulty, snapshotJson)`：建壳（`paperJson=空树 JSON`、
   `totalQuestions=0`、`questionStatus=PENDING`、`status=CREATED`、`requestId`（幂等时））；
   空树用 `PaperTree.fromFlatQuestions(List.of(), skillId).toJson(objectMapper)`；
5. `sessionCache.saveSession(sessionId, resumeText或"", resumeId, null, null,
   PaperTree.fromFlatQuestions(List.of(), skillId), 0, CREATED)`；
6. `questionGenProducer.sendQuestionGenTask(sessionId)`；投递失败时
   `failQuestionGenIfPending` 并返回失败态；
7. 返回 `InterviewSessionDTO(sessionId, resumeText, 0, 0, List.of(), CREATED, null, null,
   null, resumeId, AsyncTaskStatus.PENDING, null)`。

`createIdempotentSession(...)`：壳会话落库后**立即**
`redisService.set(resultKey, sessionId, CREATE_RESULT_TTL)`，再投递任务；重复请求命中缓存时
`getSession(cachedSessionId)` 返回当前状态。

`getSession(...)` / `toDTO`：新增按 DB 读取出题状态的逻辑：

```java
AsyncTaskStatus qs = persistenceService.findBySessionId(sessionId)
    .map(PracticeSessionEntity::getQuestionStatus)
    .orElse(AsyncTaskStatus.COMPLETED);
boolean ready = qs == AsyncTaskStatus.COMPLETED;
return new InterviewSessionDTO(..., ready ? questions : List.of(), ...,
    ready ? paperTree : null, ..., qs, ready ? null : questionError);
```

`findUnfinishedSession` 对壳会话（`CREATED` + `questionStatus=PENDING`）自然命中，返回
`questionStatus=PENDING` 的 DTO，前端据此轮询，不重复创建。

`InterviewPersistenceService.saveQuestionShell(...)` 内部复用 `saveSessionInternal` 并追加
`questionStatus / questionGenRequestJson` 两个字段赋值（或新增独立方法；两者等价，实现时二选一，
不新增第二套分层）。

#### 6.1.10 DTO 与 Controller

`InterviewSessionDTO` 追加两个字段（追加在末尾，保持构造器调用处统一更新）：

```java
AsyncTaskStatus questionStatus,
String questionError
```

`forAttemptView()` 原样透传这两个字段。

新增「重新出题」入口：

```text
POST /api/interview/sessions/{sessionId}/questions/retry
```

`InterviewSessionService.retryQuestionGeneration(sessionId)`：
仅当 `questionStatus == FAILED` 时允许，执行
`resetQuestionGenRecoveryCount(sessionId)` → 置 `questionStatus=PENDING, questionError=null`
（新增 `retryQuestionGenFromFailed(sessionId, now)` 条件更新）→
`questionGenProducer.sendQuestionGenTask(sessionId)`；否则抛
`BusinessException(ErrorCode.BAD_REQUEST, "当前状态不允许重新出题")`。

#### 6.1.11 恢复调度器与配置

`QuestionGenRecoveryScheduler`（`modules/interview/listener`）：
镜像 `PaperRecognitionRecoveryScheduler`，`@Scheduled(fixedDelayString =
"${app.async.recovery.question-gen.interval-ms:60000}")`：

- 扫 `findStaleByQuestionStatus(PENDING, now-pendingThreshold, pageable)` → 对每条先
  `incrementQuestionGenRecoveryCount`，超 `maxRecoveryCount` 则 `failQuestionGenIfPending`；
  否则 `producer.sendQuestionGenTask(sessionId)`；
- 扫 `findStaleByQuestionStatus(PROCESSING, now-processingThreshold, pageable)` →
  `resetQuestionGenToPending(sessionId, entity.getQuestionAttemptId(), now)` 成功后再补投。

新增 `QuestionGenRecoveryProperties extends AsyncTaskRecoveryProperties`
（`@ConfigurationProperties(prefix = "app.async.recovery.question-gen")`）。

`application.yml` 新增：

```yaml
interview:
  question-gen:
    executor:
      pool-size: ${APP_INTERVIEW_QUESTION_GEN_POOL_SIZE:2}
      queue-capacity: ${APP_INTERVIEW_QUESTION_GEN_QUEUE_CAPACITY:20}
      rejection-policy: ${APP_INTERVIEW_QUESTION_GEN_REJECTION_POLICY:abort}
  question-inner:
    executor:
      pool-size: ${APP_INTERVIEW_QUESTION_INNER_POOL_SIZE:4}
      queue-capacity: ${APP_INTERVIEW_QUESTION_INNER_QUEUE_CAPACITY:20}
      rejection-policy: ${APP_INTERVIEW_QUESTION_INNER_REJECTION_POLICY:abort}
async:
  recovery:
    question-gen:
      enabled: ${APP_ASYNC_RECOVERY_QUESTION_GEN_ENABLED:true}
      pending-threshold: ${APP_ASYNC_RECOVERY_QUESTION_GEN_PENDING_THRESHOLD:10m}
      processing-threshold: ${APP_ASYNC_RECOVERY_QUESTION_GEN_PROCESSING_THRESHOLD:15m}
      interval-ms: ${APP_ASYNC_RECOVERY_QUESTION_GEN_INTERVAL_MS:60000}
      batch-size: ${APP_ASYNC_RECOVERY_QUESTION_GEN_BATCH_SIZE:10}
      max-recovery-count: ${APP_ASYNC_RECOVERY_QUESTION_GEN_MAX_RECOVERY_COUNT:3}
```

### 6.2 D 前端

`frontend/src/types/interview.ts`：`InterviewSession` 增加
`questionStatus?: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';`
`questionError?: string | null;`

`frontend/src/api/interview.ts`：`createSession` 超时从 180000 降到 15000；新增
`retryQuestionGeneration(sessionId)` 调 `POST .../questions/retry`。

`frontend/src/pages/InterviewPage.tsx`：

- `startInterview` 拿到壳会话后，若 `questionStatus !== 'COMPLETED'` 进入轮询
  （`setInterval` 调 `interviewApi.getSession`，间隔 1500ms）：
  - `COMPLETED` → `initSession(s)`；
  - `FAILED` → `setError(questionError || '出题失败，请重试')`，展示「重新出题」按钮
    （调 `retryQuestionGeneration` 后回到轮询）；
- `resumeExistingSession` 同样先判断 `questionStatus`，非 `COMPLETED` 时进入轮询；
- 轮询组件卸载 / 会话变化时 `clearInterval`。

### 6.3 E 后端

#### 6.3.1 Flyway DDL（如 `V20261102__paper_input_processing.sql`）

```sql
ALTER TABLE practice_papers
  ADD COLUMN input_status varchar(20) NOT NULL DEFAULT 'COMPLETED',
  ADD COLUMN input_error varchar(500),
  ADD COLUMN input_attempt_id varchar(36),
  ADD COLUMN input_updated_at timestamp(6),
  ADD COLUMN input_recovery_count integer NOT NULL DEFAULT 0;

CREATE TABLE paper_input_files (
  id bigserial PRIMARY KEY,
  paper_id bigint NOT NULL REFERENCES practice_papers(id) ON DELETE CASCADE,
  seq int NOT NULL,
  storage_key varchar(500) NOT NULL,
  filename varchar(500),
  content_type varchar(200),
  size_bytes bigint,
  CONSTRAINT uq_paper_input_files_paper_seq UNIQUE (paper_id, seq)
);
CREATE INDEX idx_paper_input_files_paper ON paper_input_files(paper_id);
```

`input_status` 默认 `COMPLETED`：历史试卷已解析过，读作「已完成」。

#### 6.3.2 实体与 Repository

`PracticePaperEntity` 追加 `inputStatus / inputError / inputAttemptId / inputUpdatedAt /
inputRecoveryCount`（类型与 6.1.2 对齐，枚举 `AsyncTaskStatus`）。

新增 `PaperInputFileEntity`（`paperId, seq, storageKey, filename, contentType, sizeBytes`）与
`PaperInputFileRepository extends JpaRepository<PaperInputFileEntity, Long>`：

```java
List<PaperInputFileEntity> findByPaperIdOrderBySeqAsc(Long paperId);
@Transactional
@Modifying
void deleteByPaperId(Long paperId);
```

`ResumeRepository` 追加与 `tryMarkAnalyzeProcessing` 同构的一组 `input*` 方法（命名替换）：
`tryMarkInputProcessing / heartbeatInputProcessing / completeInputIfProcessing /
failInputIfProcessing / failInputIfPending / resetInputToPending / incrementInputRecoveryCount /
resetInputRecoveryCount / findStaleByInputStatus`，JPQL 中的状态字段用 `r.inputStatus`。

#### 6.3.3 Stream 常量（`AsyncTaskStreamConstants` 追加）

```java
public static final String PAPER_INPUT_STREAM_KEY = "paper:input:stream";
public static final String PAPER_INPUT_GROUP_NAME = "paper-input-group";
public static final String PAPER_INPUT_CONSUMER_PREFIX = "paper-input-consumer-";
```

#### 6.3.4 Producer / Consumer / RecoveryScheduler（`modules/resume/listener`）

`PaperInputStreamProducer` / `PaperInputStreamConsumer` / `PaperInputRecoveryScheduler` 与 6.1.6 /
6.1.11 同构：

- 消费者 `dispatchAsync → true`；`processBusiness` 把 `paperInputProcessingService.process`
  提交到 `paperInputExecutor`，`ExecutorRejectedException` 转
  `BusinessException(ErrorCode.SYSTEM_BUSY, ...)`；
- `shouldSkip` → `paper == null || inputStatus == COMPLETED`；
- worker 完成后 `completeAsyncTask(payload, retryCount, error)`；
- 恢复调度器扫描 `inputStatus` 的 stale PENDING / PROCESSING，限量补投；超限
  `failInputIfPending`。

#### 6.3.5 `PaperInputProcessingService`（新，`modules/resume/service`）

从 `PaperRecognitionService` 迁移以下私有方法（已确认只被 `doUpload` 使用：`createPages`、
`enqueueDirectExtraction`、`chargePageQuota`、`findParser`、`resolveSourceType`、`cleanup`、
`readBytes`），并新增编排入口：

```java
public void process(Long paperId, String attemptId) {
    PracticePaperEntity paper = requirePaper(paperId);
    List<InputFile> inputs = inputFileRepository.findByPaperIdOrderBySeqAsc(paperId);
    List<ParsedDocument> docs = parseAll(inputs);
    int totalPages = docs.stream().mapToInt(d -> d.pages().size()).sum();
    int visionPages = (int) docs.stream().flatMap(d -> d.pages().stream())
        .filter(p -> p.source() == PageSource.IMAGE).count();
    int textChars = docs.stream().flatMap(d -> d.pages().stream())
        .filter(p -> p.source() == PageSource.TEXT)
        .mapToInt(p -> p.lines().stream().mapToInt(String::length).sum()).sum();
    validateLimits(visionPages, textChars); // maxVisionPages / maxTextChars 在此才校验
    if (visionPages > 0) chargePageQuota(visionPages);
    // 写 sourceType / pageCount / resumeText(纯文字时) 并 createPages(...)
    if (visionPages > 0) {
        if (!recognitionProducer.sendRecognitionTask(paperId)) { failInput(...); return; }
    } else {
        enqueueDirectExtraction(paperId);
    }
    completeInputIfProcessing(paperId, attemptId, now);
}
```

`parseAll`：对每个输入文件 `storageService.download` 原始字节 → `detectContentType` →
`findParser(...).parse(...)`。下载复用 `FileStorageService` 现有能力（对齐
`ResumeParseService.downloadAndParseContent` 的取数路径）。

任一步抛异常：`cleanup(paperId, uploadedKeys)` 清理已建页 / 页图 / 资源，保留原始文件与失败原因，
由 worker 的 `completeAsyncTask(..., error)` 走 `failInputIfProcessing` 落终态。

`enqueueDirectExtraction` 由「直接构造返回结果」改为「只推进状态并投递」：写
`recognitionStatus=NONE / analysisStatus=PENDING / resumeText=merge.text()`，投递
`paper:extract:stream`；返回结果构造交给上传阶段的 DTO 查询。

#### 6.3.6 `PaperRecognitionService.doUpload` 改造（请求线程瘦身）

新流程：

1. `validateFiles`；
2. 对每个文件 `readBytes` + `detectContentType` + `calculateHash`（**不 parse**）；
3. `fileHash = 单文件自身哈希 / 多文件拼接哈希`，`findByFileHashScoped` 去重；
4. 事务内建壳：`PracticePaperEntity`（`inputStatus=PENDING`、`recognitionStatus=NONE`、
   `analysisStatus=PENDING`、`pageCount=0`、`sourceType` 暂置 `PDF`，最终由输入处理覆盖）；
5. 每个原始文件 `storageService.uploadBytes(bytes, originalFilename, contentType,
   "paper-input")` 得 `storageKey`，写 `paper_input_files`（`seq` 从 1 递增）；
6. `paperInputStreamProducer.sendInputTask(paperId)`；投递失败 → `failInputIfPending` 并返回
   `PaperUploadResult(paperId, 0, NONE, INPUT_PENDING, false, "文件已接收，但入队失败")`；
7. 成功返回 `PaperUploadResult(paperId, 0, RecognitionStatus.NONE,
   ProcessingPath.INPUT_PENDING, false, "文件已接收，正在解析")`。

`ProcessingPath` 枚举新增 `INPUT_PENDING`。

#### 6.3.7 查询 DTO 改造

`ResumeDetailDTO`（`modules/resume/model/ResumeDetailDTO.java`）与
`PaperRecognitionDTO` 各新增 `AsyncTaskStatus inputStatus` 与 `String inputError`；
`ResumeHistoryService.getResumeDetail` 与 `PaperRecognitionService.getRecognition` 从
`PracticePaperEntity` 读取填充。

#### 6.3.8 配置

新增 `PaperInputExecutorProperties extends BoundedExecutorProperties`
（`@ConfigurationProperties(prefix = "paper.input.executor")`，默认 `poolSize=2、
queueCapacity=10、ABORT`）与 `PaperInputConfiguration`（`@Bean("paperInputExecutor",
destroyMethod="shutdown")` 调 `factory.create("paper-input", properties)`）。

新增 `PaperInputRecoveryProperties extends AsyncTaskRecoveryProperties`
（`@ConfigurationProperties(prefix = "app.async.recovery.paper-input")`）。

`application.yml` 新增：

```yaml
paper:
  input:
    executor:
      pool-size: ${APP_PAPER_INPUT_POOL_SIZE:2}
      queue-capacity: ${APP_PAPER_INPUT_QUEUE_CAPACITY:10}
      rejection-policy: ${APP_PAPER_INPUT_REJECTION_POLICY:abort}
async:
  recovery:
    paper-input:
      enabled: ${APP_ASYNC_RECOVERY_PAPER_INPUT_ENABLED:true}
      pending-threshold: ${APP_ASYNC_RECOVERY_PAPER_INPUT_PENDING_THRESHOLD:10m}
      processing-threshold: ${APP_ASYNC_RECOVERY_PAPER_INPUT_PROCESSING_THRESHOLD:15m}
      interval-ms: ${APP_ASYNC_RECOVERY_PAPER_INPUT_INTERVAL_MS:60000}
      batch-size: ${APP_ASYNC_RECOVERY_PAPER_INPUT_BATCH_SIZE:10}
      max-recovery-count: ${APP_ASYNC_RECOVERY_PAPER_INPUT_MAX_RECOVERY_COUNT:3}
```

### 6.4 E 前端

`frontend/src/types/paperRecognition.ts`：`ProcessingPath` 增加 `'INPUT_PENDING'`；
`PaperUploadResult` 不变；`PaperRecognition` 增加 `inputStatus? / inputError?`。

`frontend/src/api/history.ts`：`ResumeDetail` 增加
`inputStatus?: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'; inputError?: string | null;`

`frontend/src/hooks/usePaperAnalysis.ts`（`loadPaper` 顶部）：

```ts
if (detail.inputStatus && detail.inputStatus !== 'COMPLETED') {
  if (detail.inputStatus === 'FAILED') {
    setError(detail.inputError || '文件解析失败，请重试');
  }
  setStage('input'); // 新阶段：仅轮询 /api/resumes/{id}/detail，不进入识别/切题
  return;
}
```

新增 `stage === 'input'` 的轮询 `useEffect`（复用 `POLL_INTERVAL_MS`）：
`loadPaper(paperId)` 直到 `inputStatus === 'COMPLETED'` 后按既有
`recognitionStatus / analysisStatus` 分支进入识别校对或切题进度；`FAILED` 展示重试 / 重传。

`UploadPage` / 上传组件：上传返回 `processingPath === 'INPUT_PENDING'` 时，携 `paperId`
进入试卷分析页即可（轮询由 `usePaperAnalysis` 统一驱动），文案显示「文件已接收，正在解析」。

### 6.5 端到端调用链

D：

```text
POST /api/interview/sessions
  → createSession → save shell(question_status=PENDING, snapshot) + cache + 幂等结果缓存
  → InterviewQuestionGenStreamProducer.send
  → InterviewQuestionGenStreamConsumer.tryMarkProcessing(PENDING→PROCESSING)
  → processBusiness: questionGenExecutor.execute(worker)
  → [consumer 立即 ACK，继续领取下一条]
  → worker: SessionQuestionGenerationService.generate
     → 读快照 → 重查历史题 → generateQuestionsBySkill(内部 questionInnerExecutor 并行)
     → completeQuestionGenIfProcessing(写 paper_json/totalQuestions, COMPLETED) + 刷新缓存
  → worker: completeAsyncTask(..., null) → markCompleted（幂等 no-op）
前端轮询 getSession → questionStatus=COMPLETED → initSession
```

E：

```text
POST /api/papers/upload
  → validate + readBytes + hash + dedup
  → save paper shell(input_status=PENDING) + upload originals + save paper_input_files
  → PaperInputStreamProducer.send
  → PaperInputStreamConsumer.tryMarkProcessing(PENDING→PROCESSING)
  → processBusiness: paperInputExecutor.execute(worker)
  → [consumer 立即 ACK，继续领取下一条]
  → worker: PaperInputProcessingService.process
     → download + parse + createPages + chargePageQuota
     → branch: recognition stream / direct extract
  → completeInputIfProcessing(COMPLETED)
前端轮询 /api/resumes/{id}/detail → inputStatus=COMPLETED → 进入识别或切题阶段
```

### 6.6 可改点映射

- D 出题并发会话数：`interview.question-gen.executor.*`；
- D 出题内部并行：`interview.question-inner.executor.*`；
- D 恢复：`app.async.recovery.question-gen.*`；
- E 解析并发：`paper.input.executor.*`；
- E 恢复：`app.async.recovery.paper-input.*`；
- 原始件解析后是否删除：`PaperInputProcessingService` 的清理策略开关；
- 前端轮询间隔：`InterviewPage` / `usePaperAnalysis` 的轮询常量。

## 7. 测试与验收

### 7.1 D

- 创建会话接口在无 LLM 调用时毫秒级返回，`questionStatus=PENDING`；
- 出题完成后 `paper_json` / `totalQuestions` 正确，缓存与 DB 一致；
- 幂等请求同一 `requestId` 只出一次题；重复点击返回同一个壳会话；
- 出题失败显示原因，「重新出题」可恢复；
- 恢复调度器能重置并补投卡死的 `PROCESSING`；
- 有界执行器队列满时按 `ABORT` 拒绝并进入「系统繁忙」失败，不无限堆积；
- 出题中访问答题页：前端只显示「题目生成中」，不渲染提交入口。

### 7.2 E

- 上传接口毫秒级返回，原始文件与 `paper_input_files` 已落库，`processingPath=INPUT_PENDING`；
- 纯文字卷：输入完成后直切，`recognitionStatus=NONE`；
- 扫描卷：输入完成后投递识别，图片页 PENDING；
- 混合卷：文字页 READY、图片页 PENDING，合并为一份；
- 解析失败：`inputStatus=FAILED`，原始文件保留，重试可恢复；
- 去重仍按 `(owner, fileHash)` 生效；
- 恢复调度器能重置并补投卡死任务；
- 队列满：`inputStatus=FAILED` 且原因可读。

### 7.3 分级

- D / E 都涉及跨模块、数据表、Redis Stream、并发与事务，按 T3 执行：全量
  `./gradlew :app:test --no-daemon` + 前端 `pnpm run build`；
- 全量回归完成后必须给失败清单与处置，不允许带着新失败收尾。
