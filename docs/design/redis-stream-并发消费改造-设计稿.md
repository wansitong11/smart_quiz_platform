# Redis Stream 并发消费改造 - 设计稿

> 状态：已实现，定向测试通过（2026-11-02）；语音评估的条件状态更新与缓存失效已收口到 Service；
> 全量回归 672 例中 25 个失败仍为
> `docs/待优化事项.md` §3.1 登记的知识库旧失败，本轮无新增失败类别
> 目标：把 Redis Stream 消费者从“单个消费线程串行执行整条业务”升级为
> “单个分发线程读取消息，有界在途队列缓冲，多个 worker 并发执行业务，业务终态后再 ACK”。
> 本文正文供产品/开发评审；附录是可零猜测实现的工程规格。

## 一、结论摘要

1. Redis Stream 继续作为唯一持久任务队列；本地在途队列只保存已经读取但尚未处理完的消息。
2. 每个 Stream 只有 1 条分发线程，负责读取、解析和入队，不执行重业务。
3. worker 从在途队列取消息后先做原子领取，再执行该 Stream 原有业务方法。
4. 业务成功或确定性失败并写入终态后，worker 才 ACK；瞬时失败则先重投新消息，再 ACK 旧消息。
5. worker 池由统一的 `StreamWorkerRegistry` 按消费者 key 创建和管理，每个功能仍可独立配置线程数、
   队列容量和停机等待时间。
6. 消费者外层并发与业务内部并发是两层不同能力。识别、出题等业务仍可保留自己的内部线程池。
7. D/E 当前的“提前 ACK + DB 恢复”模式迁移到新模型；其他同步消费者逐步迁移。

## 二、目的

当前所有消费者共用一个单线程消费框架。重业务直接占住消费线程时，一份试卷识别、一次知识库出题
或一份向量化任务只要耗时较长，后面的消息就只能等待。系统虽然有 Redis Stream 和消费者组，
单实例内实际上仍是串行处理。

本改造要让同一类任务在一个应用实例内可以并行处理，同时不破坏以下现有保障：

- 同一份业务数据不会被两个不同代次同时处理；
- 进程崩溃后，未完成消息仍能从 Redis Pending List 恢复；
- 任务失败原因、重试次数和最终状态仍然可查；
- 每个功能可以独立控制并发，不能一刀切把整个系统线程数放大。

## 三、触发与适用范围

触发条件：

- 应用启动后，每个 Stream 消费者创建 1 条分发线程；
- Redis Stream 中出现新消息；
- Redis Pending List 中存在超过空闲阈值的可回收消息。

本期覆盖项目内所有继承 `AbstractStreamConsumer` 的消费者：

- 面试出题；
- 面试评估；
- 试卷输入解析；
- 试卷识别；
- 试卷切题；
- 知识库出题；
- 知识库向量化；
- 语音面试评估。

不在本期范围：

- 不替换 Redis Stream，也不引入第二套 MQ；
- 不改变 Prompt、模型、评分或资源归属规则；
- 不新增数据库表；
- 不改变生产者发送的消息字段，除 D/E 不再由消费者自行维护外层 worker 池；
- 不要求业务内部停止使用自己的 `BoundedExecutor`。

## 四、目标运行模型

```text
Redis Stream + Consumer Group
          |
          | 1 条分发线程读取 / 解析 / 入队
          v
  有界在途队列（本地，内存）
          |
          +---- worker-1 ----> 原子领取 -> 业务 -> 终态 -> ACK
          +---- worker-2 ----> 原子领取 -> 业务 -> 终态 -> ACK
          +---- worker-N ----> 原子领取 -> 业务 -> 终态 -> ACK
```

同一个 Stream 的分发线程固定为 1。并行度由 worker 数量决定。需要扩大总吞吐时，可以同时增加
worker 数量和应用实例数；多实例仍共用 Redis Consumer Group。

## 五、正文：业务规则

### 5.1 分发线程只负责搬运

- 目的：避免 Redis 读取被慢业务卡住。
- 规则：分发线程按批次读取消息，只做消息格式解析和入队；格式错误的消息直接 ACK 丢弃。
- 结果：分发线程可以快速把后续消息放入有界队列。
- 边界：在途队列满时，分发线程等待空位，不再继续从 Redis 拉取。
- 实现：`AbstractStreamConsumer.processMessage(...)` 只做 `parsePayload` 和
  `workerPool.submitBlocking(...)`；解析失败或返回空时直接 `lease.ack()`。读取循环仍是单线程
  `consumeLoop()`，不在这里调用 `processBusiness`。

### 5.2 worker 负责领取和执行业务

- 目的：让多个任务真正并行，同时保留同一业务数据的防重复能力。
- 规则：worker 出队后先做原子领取；领取成功才执行原业务方法。
- 结果：不同消息可以由不同 worker 同时执行；同一业务数据只有领取成功的 worker 能继续。
- 边界：任务已被其他代次领取时，本条重复消息直接 ACK 丢弃。

原子领取放在 worker 内，而不是分发线程入队前。批改和语音评估也必须使用
`PENDING -> PROCESSING` 条件更新，不能使用无条件覆盖写。这样即使进程在“刚读到消息、还没执行”时崩溃，
消息仍处于未完成状态，不会出现“数据库已经 PROCESSING，但本地任务还没入队”的不可恢复窗口。

- 实现：worker 走 `executeWorker(...)`：先 `shouldSkip(payload)` / `tryMarkProcessing(payload)`，
  后者是 `UPDATE ... WHERE status='PENDING'` 条件更新，返回 false 表示已被领取；成功后执行
  `processBusiness(payload)`，再 `markCompleted(payload)`。

### 5.3 消息怎么结束

- 业务成功：先写完成状态，再 ACK。
- 确定性失败：先写失败状态和可读原因，再 ACK，不继续重投。
- 瞬时失败且未超过重试上限：先投递带新重试次数的新消息，再 ACK 旧消息。
- 写终态或重投本身失败：不 ACK，消息留在 Redis Pending List，等待自动回收。
- ACK 失败：只记日志和指标，不反向把已经成功的业务改成失败。
- 带会话缓存的状态迁移必须通过业务 Service 收口：条件更新命中后才清理缓存，并在数据库提交后执行；
  缓存清理失败只记录告警，不把已经成功的数据库状态迁移反向判定为业务失败。
- 实现：`executeWorker` 的 `finally` 只在 `safeToAck=true` 时调 `lease.ack()`；`ack()` 内部调
  `redisService.streamAck(...)`，异常只 `recordAckFailure()` 不抛出。`handleTaskFailure` 负责
  `retryMessage` 与 `markFailed` 的分支。

### 5.4 背压

- 目的：防止突发消息把 JVM 内存打满。
- 规则：每个 Stream 的在途容量等于“正在执行数 + 等待队列数”，由 worker 数和队列容量共同决定。
- 结果：容量满时，分发线程停在入队动作上，Redis 中未读取的消息继续等待。
- 边界：停止应用时，分发线程不再等待空位；未入队、未完成的消息继续保持未 ACK。
- 实现：`StreamWorkerPool` 构造时建 `Semaphore(workerThreads + queueCapacity)`；
  `submitBlocking` 用 `tryAcquire(200, MILLISECONDS)` 循环，每轮检查 `keepRunning.get()`，
  任务结束在 `finally` 释放许可。

### 5.5 失败隔离

- 一个 worker 的业务失败只处理它自己的消息，不影响其他 worker 的消息。
- ACK 以消息 ID 为单位，不按批次整批确认。
- 同一 worker 池内不共享业务事务；worker 之间没有共享可变业务状态。
- 实现：每个任务独立执行 `executeWorker`，ACK 以 `StreamMessageLease.messageId()` 为单位；
  `StreamWorkerPool` 只共享计数器和信号量，不共享业务事务。

### 5.6 业务内部并发仍然保留

- 外层 worker 并发控制“同时处理多少个任务”。
- 业务内部线程池控制“一个任务内部并行多少个子步骤”。
- 识别仍是“一份试卷一个外层任务，内部按页并行”；出题仍是“一个会话一个外层任务，内部按题目准备并行”。
- 识别外层默认只给 1 个 worker，避免外层整卷并发与内层页并发相乘后突破视觉 provider 配额。
- 消费者自身不得保存跨消息共享的可变任务状态；例如向量化心跳时间必须跟随单条 payload。
- 实现：外层 `StreamWorkerPool` 的 `worker-threads` 控制同时执行的任务数；内层仍是各业务自己的
  `BoundedExecutor`（如识别的页级执行器），两者独立配置，默认值见附录 E。

### 5.7 优雅停机

- 应用停止时，分发线程停止读取新消息。
- 已经在途的任务在配置的等待时间内尽量执行完并 ACK。
- 等待超时后仍未完成的任务不 ACK，留在 Redis Pending List，由其他实例或下次启动回收。
- 已经在 BoundedExecutor 队列中但未开始的任务，不能在未执行的情况下提前 ACK。
- 实现：`shutdown()` 先 `running.set(false)`，再 `executorService.shutdownNow()` 中断读取；
  然后 `workerPool.awaitIdle(drainTimeout)`；超时调 `stopAcking()`，之后 `lease.ack()` 只记日志、
  不再 ACK。`workerPool` 由 `StreamWorkerRegistry` 统一关闭。

### 5.8 配置与观测

- 每个功能可以单独配置 worker 数、队列容量和停机等待时间。
- 默认值统一提供，未单独配置的 Stream 使用默认值。
- 观测至少包括：分发数、完成数、失败数、重投数、ACK 失败数、worker 在途数、队列深度和 Redis Pending 数。
- 实现：`StreamConsumerProperties` 前缀 `app.async.stream`，`defaults` 与
  `consumers.<consumer-key>` 合并；`StreamWorkerRegistry` 用
  `BoundedExecutorFactory.create("stream-" + consumerKey, spec)` 建池；指标由
  `StreamWorkerPool` 计数器和定时 `streamPendingCount(...)` 写入 Micrometer。

## 六、迁移顺序

### 阶段 1：基建

- 新增 `StreamMessageLease`、`StreamWorkerPool`、`StreamWorkerRegistry`、`StreamConsumerProperties`。
- 重构 `AbstractStreamConsumer`，将消息执行生命周期收口到 worker。
- 新增并发、背压、ACK 隔离、停机恢复测试。

### 阶段 2：D/E 样板迁移

- 面试出题消费者移除自己的外层 `questionGenExecutor`，保留内部出题执行器。
- 试卷输入消费者移除自己的 `paperInputExecutor`。
- 同步调整配置和测试。

### 阶段 3：逐条迁移

建议顺序：

1. 试卷切题；
2. 试卷识别；
3. 面试评估；
4. 知识库出题；
5. 知识库向量化；
6. 语音面试评估。

每迁移一条，先跑该消费者及其服务的定向测试，再进入下一条。

## 七、边界与异常

| 场景 | 处理 |
| --- | --- |
| 消息字段缺失或无法解析 | 分发线程记录日志并 ACK，不进入队列 |
| 业务实体已删除 | worker 跳过业务并 ACK |
| 原子领取失败 | 视为重复或过期消息，直接 ACK |
| 在途队列满 | 分发线程等待；应用停止时放弃等待且不 ACK |
| worker 执行中进程崩溃 | 消息未 ACK，保留在 Pending List，自动回收 |
| 业务失败 | 按现有 `BusinessException` / 瞬时异常规则决定失败或重投 |
| 写终态失败 | 不 ACK，留在 Pending List |
| 重投失败 | 不 ACK 旧消息，留在 Pending List |
| ACK 失败 | 只记录指标；重复投递时由 `shouldSkip` 或领取失败兜底 |
| 停机等待超时 | 未完成任务不 ACK，下次恢复 |
| 业务内部线程池满 | 仍按原有业务异常/重试规则处理，不影响其他外层 worker |

## 八、测试与验收

### 8.1 单元测试

- 两条消息并发执行，慢任务不阻塞快任务；
- 一条消息失败，另一条仍能成功并独立 ACK；
- 队列满时第三个提交被阻塞，释放一个容量后继续；
- ACK 失败不抛业务异常，只累加指标；
- 写终态失败时不 ACK；
- 停机时未完成任务不被 ACK；
- 每个 consumer key 获得独立 worker 池和配置；
- 未配置的 Stream 使用默认并发参数。

### 8.2 定向测试

- D/E 原有异步测试继续通过；
- 试卷切题、识别、知识库出题、向量化各做一条样例消费测试；
- 同一消息重复投递只执行一次业务。

### 8.3 全量回归

本改造涉及 `common/async`、Redis Stream、并发和停机，属于 T3：

- `./gradlew :app:test --no-daemon`
- 前端无接口变化，不要求重新构建；如果改到前端状态展示，再跑 `pnpm run build`
- 全量失败必须列出清单并说明是否为本轮新增

### 8.4 人工验收

- 连续上传两份试卷，两份可以并行进入输入解析；
- 同时发起两个会话出题，两个会话可以并行生成；
- 一份识别任务内部仍能按页并行；
- 人为制造一个业务失败，确认只有该消息进入失败/重投，其他任务不受影响；
- 停止应用时确认在途任务的 ACK 行为符合预期。

## 九、可改点

- 每个 Stream 的 worker 数：`app.async.stream.consumers.<consumer-key>.worker-threads`
- 每个 Stream 的等待队列容量：`app.async.stream.consumers.<consumer-key>.queue-capacity`
- 停机等待时间：`app.async.stream.consumers.<consumer-key>.drain-timeout`
- Pending 指标采样周期：`app.async.stream.pending-metrics-interval-ms`
- 是否传播登录上下文：`app.async.stream.consumers.<consumer-key>.propagate-security-context`

## 十、附录：实现规格

### A. 类与接口

#### A.1 `StreamMessageLease`

包路径：

`app/src/main/java/interview/guide/common/async/StreamMessageLease.java`

签名：

```java
public final class StreamMessageLease {
    public StreamMessageId messageId();
    public void ack();
}
```

字段：

- `RedisService redisService`
- `StreamWorkerPool workerPool`
- `String streamKey`
- `String groupName`
- `String consumerName`
- `StreamMessageId messageId`

`ack()` 行为：

1. 调 `redisService.streamAck(streamKey, groupName, messageId)`；
2. 成功时不做额外动作；
3. 异常时记录 error 日志，调用 `workerPool.recordAckFailure()`，不向调用方抛出。

#### A.2 `StreamWorkerPool`

包路径：

`app/src/main/java/interview/guide/common/async/StreamWorkerPool.java`

职责：

- 包装一个功能专属 `BoundedExecutor`；
- 用 `Semaphore` 提供有界阻塞投递；
- 统计在途任务数、完成数、失败数、重投数和 ACK 失败数；
- 在停机时等待在途任务结束。

签名：

```java
public final class StreamWorkerPool implements AutoCloseable {
    StreamWorkerPool(String consumerKey,
                     String streamKey,
                     String groupName,
                     BoundedExecutor executor,
                     StreamConsumerProperties.WorkerSettings settings,
                     ObjectProvider<MeterRegistry> meterRegistryProvider);

    public boolean submitBlocking(Runnable task, BooleanSupplier keepRunning);
    public boolean awaitIdle(Duration timeout);
    public Duration drainTimeout();
    public int inFlight();
    public int queueDepth();
    public boolean ackAllowed();
    public void recordCompleted();
    public void recordFailed();
    public void recordRetried();
    public void recordAckFailure();
    public void stopAcking();
    public void shutdown();
}
```

实现约束：

1. 构造时创建 `Semaphore(workerThreads + queueCapacity)`；
2. `submitBlocking` 循环调用 `tryAcquire(200, MILLISECONDS)`；
3. 每轮检查 `keepRunning.get()`；为 false 或线程被中断时返回 false；
4. 获得许可后把任务提交给 `BoundedExecutor.execute`；
5. worker 任务无论正常/异常结束，都在 `finally` 释放许可并减少在途计数；
6. 提交失败时释放许可、减少在途计数，返回 false；
7. `awaitIdle` 在在途计数归零时返回 true，超过 timeout 返回 false；
8. `awaitIdle` 使用 `AtomicInteger inFlight` 和 50ms 轮询实现；
9. `drainTimeout()` 返回当前 worker 配置的 `drainTimeout`；
10. `shutdown` 调底层 `BoundedExecutor.shutdown()`。
11. 停机等待超时后调用 `stopAcking()`；ACK 句柄发现池已停止 ACK 时只记日志，不确认消息。

#### A.3 `StreamWorkerRegistry`

包路径：

`app/src/main/java/interview/guide/common/async/StreamWorkerRegistry.java`

签名：

```java
@Component
public final class StreamWorkerRegistry implements AutoCloseable {
    StreamWorkerRegistry(BoundedExecutorFactory executorFactory,
                         StreamConsumerProperties properties,
                         RedisService redisService,
                         ObjectProvider<MeterRegistry> meterRegistryProvider);

    public StreamWorkerPool acquire(String consumerKey,
                                    String streamKey,
                                    String groupName);
}
```

行为：

1. 按 `consumerKey` 从 `ConcurrentHashMap` 取值；
2. 不存在时调用 `StreamConsumerProperties.resolve(consumerKey)` 得到配置；
3. 用 `BoundedExecutorFactory.create("stream-" + consumerKey, spec)` 创建底层池；
4. 用 `StreamWorkerPool` 包装并缓存；
5. 同一个 key 重复调用返回同一个池；
6. 同一个 key 对应不同 `streamKey` 或 `groupName` 时抛 `IllegalStateException`；
7. `@PreDestroy` 时先对每个池执行 `awaitIdle(drainTimeout)`，再关闭所有池；
8. 定时采样方法使用
   `@Scheduled(fixedDelayString = "${app.async.stream.pending-metrics-interval-ms:30000}")`。

#### A.4 `StreamConsumerProperties`

包路径：

`app/src/main/java/interview/guide/common/async/StreamConsumerProperties.java`

配置前缀：

`app.async.stream`

字段：

```java
private long pendingMetricsIntervalMs = 30000;
private WorkerSettings defaults = new WorkerSettings();
private Map<String, WorkerSettings> consumers = new LinkedHashMap<>();
```

`WorkerSettings` 字段：

```java
@Min(1) @Max(32)
private int workerThreads = 2;

@Min(1)
private int queueCapacity = 20;

@NotNull
private Duration drainTimeout = Duration.ofSeconds(10);

private boolean propagateSecurityContext = true;
```

`resolve(consumerKey)`：

1. 读取 `consumers.get(consumerKey)`；
2. 未配置时返回 defaults 的复制值；
3. 配置项没有设置的字段由 defaults 补齐；
4. 对 workerThreads、queueCapacity、drainTimeout 做正数校验；
5. 返回不可变副本，不修改 defaults 或原配置对象。

`StreamWorkerRegistry` 将该配置转换成以下私有子类：

```java
private static final class ResolvedExecutorProperties
        extends BoundedExecutorProperties {
}
```

- `poolSize = workerThreads`
- `queueCapacity = queueCapacity`
- `rejectionPolicy = ExecutorRejectionPolicy.ABORT`
- `propagateSecurityContext = 配置值`

### B. `AbstractStreamConsumer` 改造

包路径：

`app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java`

构造器改为：

```java
protected AbstractStreamConsumer(RedisService redisService,
                                 StreamWorkerRegistry workerRegistry)
```

新增抽象方法：

```java
protected abstract String consumerKey();
```

`@PostConstruct init()` 顺序：

1. 设置 consumerName：`consumerPrefix() + UUID 前 8 位`；
2. 创建 `workerPool = workerRegistry.acquire(consumerKey(), streamKey(), groupName())`；
3. 创建固定 1 线程的 claim loop；
4. 创建 Redis Consumer Group；
5. 启动 `consumeLoop()`。

`processMessage(StreamMessageId, Map)` 改为：

1. `parsePayload` 异常或返回 null：记录日志，直接 ACK；
2. 构造 `StreamMessageLease`；
3. 调 `workerPool.submitBlocking(() -> executeWorker(...), running::get)`；
4. `submitBlocking` 返回 false：不 ACK，记录日志；
5. 返回 true：分发线程继续处理下一条。

`executeWorker` 必须按以下伪代码实现：

```java
private void executeWorker(StreamMessageLease lease,
                           T payload,
                           int retryCount) {
    boolean safeToAck = false;
    try {
        if (shouldSkip(payload) || !tryMarkProcessing(payload)) {
            safeToAck = true;
            return;
        }
        processBusiness(payload);
        markCompleted(payload);
        workerPool.recordCompleted();
        safeToAck = true;
    } catch (Exception e) {
        try {
            handleTaskFailure(payload, retryCount, e);
            safeToAck = true;
        } catch (Exception stateError) {
            log.error("写入任务终态失败，保留消息 Pending: payload={}",
                payloadIdentifier(payload), stateError);
        }
    } finally {
        if (safeToAck) {
            lease.ack();
        }
    }
}
```

现有方法保持不变：

- `shouldSkip`
- `markProcessing`
- `tryMarkProcessing`
- `processBusiness`
- `markCompleted`
- `markFailed`
- `retryMessage`
- `handleTaskFailure`
- `describeFailure`

删除：

- `dispatchAsync`
- `completeAsyncTask`

`handleTaskFailure` 在 `retryMessage` 分支调用 `workerPool.recordRetried()`；
在 `markFailed` 分支调用 `workerPool.recordFailed()`。

`shutdown()`：

1. `running.set(false)`；
2. 调 `executorService.shutdownNow()`，中断阻塞读取；
3. 调 `workerPool.awaitIdle(workerPool.drainTimeout())`；
4. 超时只记日志，不强制 ACK；
5. 不在这里关闭 `workerPool`，由 `StreamWorkerRegistry` 统一关闭。

### C. `RedisService` 新增方法

包路径：

`app/src/main/java/interview/guide/infrastructure/redis/RedisService.java`

新增：

```java
public long streamPendingCount(String streamKey, String groupName) {
    RStream<String, String> stream =
        redissonClient.getStream(streamKey, StringCodec.INSTANCE);
    return stream.getPendingInfo(groupName).getTotal();
}
```

Pending 指标由 `StreamWorkerRegistry` 按
`app.async.stream.pending-metrics-interval-ms` 定时采样，写入 Micrometer Gauge。
采样失败时保留上一次值并记录 warn 日志。

### D. 指标清单

所有指标 tag：`consumer=<consumerKey>`、`stream=<streamKey>`。

| 指标 | 类型 | 含义 |
| --- | --- | --- |
| `app.stream.dispatched` | Counter | 成功放入在途队列的消息数 |
| `app.stream.completed` | Counter | 业务完成并准备 ACK 的消息数 |
| `app.stream.failed` | Counter | 确定性失败并准备 ACK 的消息数 |
| `app.stream.retried` | Counter | 重投新消息并准备 ACK 旧消息的次数 |
| `app.stream.ack.failure` | Counter | ACK 失败次数 |
| `app.stream.worker.inflight` | Gauge | 在途任务数 |
| `app.stream.worker.queue` | Gauge | 等待队列深度 |
| `app.stream.pending` | Gauge | Redis Pending 消息数，定时采样 |

### E. 消费者 key 与默认配置

每个消费者新增：

```java
@Override
protected String consumerKey() {
    return "<下表 key>";
}
```

| 消费者类 | consumerKey | 默认 workerThreads | 默认 queueCapacity |
| --- | --- | ---: | ---: |
| `InterviewQuestionGenStreamConsumer` | `interview-question-gen` | 2 | 20 |
| `EvaluateStreamConsumer` | `interview-evaluate` | 2 | 20 |
| `PaperInputStreamConsumer` | `paper-input` | 2 | 10 |
| `PaperRecognitionStreamConsumer` | `paper-recognition` | 1 | 2 |
| `PaperExtractionStreamConsumer` | `paper-extract` | 2 | 10 |
| `QuestionGenStreamConsumer` | `knowledgebase-question-gen` | 2 | 20 |
| `VectorizeStreamConsumer` | `knowledgebase-vectorize` | 2 | 20 |
| `VoiceEvaluateStreamConsumer` | `voice-evaluate` | 1 | 5 |

所有消费者构造器把：

```java
super(redisService);
```

改为：

```java
super(redisService, workerRegistry);
```

并在构造参数中增加 `StreamWorkerRegistry workerRegistry`。

### F. D/E 迁移细节

#### F.1 面试出题

文件：

`app/src/main/java/interview/guide/modules/interview/listener/InterviewQuestionGenStreamConsumer.java`

- 删除 `questionGenExecutor` 字段和构造参数；
- 删除 `dispatchAsync` 覆写；
- `processBusiness` 改为直接同步调用
  `generationService.generate(payload.sessionId(), payload.attemptId())`；
- `markCompleted` 保持空实现。

删除：

- `InterviewQuestionExecutorConfiguration.questionGenExecutor`
- `InterviewQuestionGenExecutorProperties`
- `app.interview.question-gen.executor.*`

保留：

- `questionInnerExecutor`
- `InterviewQuestionInnerExecutorProperties`
- `app.interview.question-inner.executor.*`

#### F.2 试卷输入

文件：

`app/src/main/java/interview/guide/modules/resume/listener/PaperInputStreamConsumer.java`

- 删除 `paperInputExecutor` 字段和构造参数；
- 删除 `dispatchAsync` 覆写；
- `processBusiness` 改为直接同步调用
  `inputProcessingService.process(payload.paperId(), payload.attemptId())`；
- 删除 `ExecutorRejectedException` 的 `SYSTEM_BUSY` 包装。

删除：

- `PaperInputConfiguration`
- `PaperInputExecutorProperties`
- `app.paper.input.executor.*`

### G. 配置样例

```yaml
app:
  async:
    stream:
      pending-metrics-interval-ms: ${APP_STREAM_PENDING_METRICS_INTERVAL_MS:30000}
      defaults:
        worker-threads: ${APP_STREAM_DEFAULT_WORKER_THREADS:2}
        queue-capacity: ${APP_STREAM_DEFAULT_QUEUE_CAPACITY:20}
        drain-timeout: ${APP_STREAM_DEFAULT_DRAIN_TIMEOUT:10s}
        propagate-security-context: ${APP_STREAM_DEFAULT_PROPAGATE_SECURITY_CONTEXT:true}
      consumers:
        interview-question-gen:
          worker-threads: ${APP_STREAM_INTERVIEW_QUESTION_GEN_WORKERS:2}
          queue-capacity: ${APP_STREAM_INTERVIEW_QUESTION_GEN_QUEUE:20}
          drain-timeout: ${APP_STREAM_INTERVIEW_QUESTION_GEN_DRAIN:10s}
        interview-evaluate:
          worker-threads: ${APP_STREAM_INTERVIEW_EVALUATE_WORKERS:2}
        paper-input:
          worker-threads: ${APP_STREAM_PAPER_INPUT_WORKERS:2}
          queue-capacity: ${APP_STREAM_PAPER_INPUT_QUEUE:10}
        paper-extract:
          worker-threads: ${APP_STREAM_PAPER_EXTRACT_WORKERS:2}
          queue-capacity: ${APP_STREAM_PAPER_EXTRACT_QUEUE:10}
        paper-recognition:
          worker-threads: ${APP_STREAM_PAPER_RECOGNITION_WORKERS:1}
          queue-capacity: ${APP_STREAM_PAPER_RECOGNITION_QUEUE:2}
        knowledgebase-question-gen:
          worker-threads: ${APP_STREAM_KB_QUESTION_GEN_WORKERS:2}
        knowledgebase-vectorize:
          worker-threads: ${APP_STREAM_KB_VECTORIZE_WORKERS:2}
        voice-evaluate:
          worker-threads: ${APP_STREAM_VOICE_EVALUATE_WORKERS:1}
```

### H. 完整状态与分支

```text
分发线程读取消息
  ├─ 消息格式错误 → ACK 丢弃
  ├─ 消费者停止 → 不 ACK，结束分发
  ├─ 在途队列满 → 等待；停止信号出现则不 ACK
  └─ 入队成功
       └─ worker 开始
            ├─ shouldSkip=true → ACK
            ├─ tryMarkProcessing=false → ACK
            ├─ 业务成功 → markCompleted → ACK
            ├─ 确定性失败 → markFailed → ACK
            ├─ 瞬时失败未超限 → retryMessage → ACK
            ├─ 重试超限 → markFailed → ACK
            ├─ 写终态/重投抛错 → 不 ACK
            └─ ACK 失败 → 消息保持 Pending；下次重复投递时按 shouldSkip/领取失败处理
```

### I. 测试文件清单

新增：

- `app/src/test/java/interview/guide/common/async/StreamWorkerPoolTest.java`
- `app/src/test/java/interview/guide/common/async/StreamWorkerRegistryTest.java`
- `app/src/test/java/interview/guide/common/async/AbstractStreamConsumerConcurrencyTest.java`
- `app/src/test/java/interview/guide/common/async/StreamConsumerPropertiesTest.java`
- `app/src/test/java/interview/guide/modules/interview/listener/EvaluateStreamConsumerStatusTest.java`
- `app/src/test/java/interview/guide/modules/voiceinterview/repository/VoiceInterviewSessionRepositoryTest.java`

修改：

- 现有各 Stream Consumer 测试，只调整构造参数和移除旧 executor mock；
- D/E 原有异步测试保持行为断言；
- `VoiceInterviewEvaluationRecoveryTest` 增加条件状态命中和未命中时的缓存失效断言。

用例最低要求：

1. 两条消息并发执行，慢消息不阻塞快消息；
2. 一条消息失败不影响另一条消息 ACK；
3. 队列满时阻塞，释放容量后继续；
4. ACK 失败不抛异常；
5. 写终态失败不 ACK；
6. 停机超时后未完成任务不 ACK；
7. 相同 consumerKey 返回同一池，不同 key 返回不同池；
8. 配置缺省合并正确；
9. D/E 不再依赖外层自定义 executor。

### J. 回滚方案

如果上线后出现严重问题：

1. 将相关 consumer 的 `worker-threads` 配置为 `1`，恢复单并发；
2. 将 `queue-capacity` 配置为 `1`，限制在途数量；
3. 若问题只在某个消费者，回退该消费者到旧同步实现，不影响其他已经迁移的消费者；
4. 已迁移并写入终态的消息无需回滚数据；未 ACK 的消息继续留在 Redis Pending List。

