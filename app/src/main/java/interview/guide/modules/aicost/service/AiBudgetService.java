package interview.guide.modules.aicost.service;

import interview.guide.common.ai.AiCostControl;
import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.exception.AiBudgetExceededException;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aicost.AiOperationStatus;
import interview.guide.modules.aicost.model.AiUsageOperationEntity;
import interview.guide.modules.aicost.repository.AiUsageOperationRepository;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 用户 AI 成本配额：月度总量池 + 动态人均额度 + 人民币记账。
 *
 * <p>免费用户共享管理员配置的月度总量池，人均额度 = 总量 ÷ 免费用户数，实时计算；
 * 管理员与授权用户豁免。计数键带 {@code yyyyMM}，跨月自动改用新键，
 * 因此“自然月重置”不需要额外定时任务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBudgetService implements AiCostControl {

  private static final BigDecimal MICRO = BigDecimal.valueOf(1_000_000);
  /** 仅作清理兜底；真正的月度重置由 key 里的 yyyyMM 完成。 */
  private static final long MONTH_TTL_SECONDS = 45L * 86400L;
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
  private static final String OP_CALLS_PREFIX = "ai:op:calls:";
  private static final String OP_COST_PREFIX = "ai:op:cost:";
  private static final String OP_RESERVE_LOCK_PREFIX = "ai:op:reserve:lock:";
  private static final long RESERVE_LOCK_WAIT_SECONDS = 3L;
  private static final long RESERVE_LOCK_LEASE_SECONDS = 30L;
  private static final long PERCENT_SCALE = 1_000_000L;

  private final RedissonClient redissonClient;
  private final AiUsageOperationRepository operationRepository;
  private final AiCostEstimator estimator;
  private final UserAccessPolicy accessPolicy;
  private final AiQuotaConfigService quotaConfigService;
  private final AiMonthlyUsageService monthlyUsageService;

  private RScript rScript;
  private String reserveSha;
  private String settleSha;
  private String releaseSha;
  private String seedSha;

  public enum BudgetDecision {
    PASS,
    SOFT_LIMIT,
    HARD_LIMIT
  }

  @PostConstruct
  void init() throws Exception {
    rScript = redissonClient.getScript(StringCodec.INSTANCE);
    reserveSha = rScript.scriptLoad(load("scripts/ai_budget_reserve.lua"));
    settleSha = rScript.scriptLoad(load("scripts/ai_budget_settle.lua"));
    releaseSha = rScript.scriptLoad(load("scripts/ai_budget_release.lua"));
    seedSha = rScript.scriptLoad(load("scripts/ai_budget_seed.lua"));
  }

  /**
   * 预占一次操作的估算成本。豁免用户只记录操作、不占池；超过人均额度返回
   * {@link BudgetDecision#HARD_LIMIT}，且不留下预占。
   */
  public BudgetDecision reserve(String operationId, Long userId, AiFeature feature,
      BigDecimal estimatedCny) {
    boolean exempt = accessPolicy.isExempt(userId);
    AiUsageOperationEntity operation =
        saveOperation(operationId, userId, feature, estimatedCny, exempt);
    if (exempt || userId == null) {
      return BudgetDecision.PASS;
    }
    try {
      BigDecimal quota = quotaConfigService.perUserCny();
      if (quota == null || quota.signum() <= 0) {
        return BudgetDecision.PASS;
      }
      seedMonthlyUsage(userId, quota);
      int result = reserveMonth(userId, toMicro(estimatedCny), toMicro(quota));
      if (result < 0) {
        markReleased(operation);
        return BudgetDecision.HARD_LIMIT;
      }
      return result == 1 ? BudgetDecision.SOFT_LIMIT : BudgetDecision.PASS;
    } catch (RuntimeException e) {
      markReleased(operation);
      throw e;
    }
  }

  private AiUsageOperationEntity saveOperation(String operationId, Long userId, AiFeature feature,
      BigDecimal estimatedCny, boolean exempt) {
    AiUsageOperationEntity operation = AiUsageOperationEntity.builder()
        .operationId(operationId)
        .userId(userId)
        .feature(feature.name())
        .source("SYNC")
        .status(AiOperationStatus.RESERVED.name())
        .estimatedCost(estimatedCny)
        .actualCost(null)
        .currency("CNY")
        .exempt(exempt)
        .startedAt(Instant.now())
        .build();
    operationRepository.save(operation);
    return operation;
  }

  private void markReleased(AiUsageOperationEntity operation) {
    operation.setStatus(AiOperationStatus.RELEASED.name());
    operation.setSettledAt(Instant.now());
    operationRepository.save(operation);
  }

  @Override
  public void reserveOrThrow(String operationId, Long userId, AiFeature feature,
      AiCostEstimateInput input) {
    if (operationId == null) {
      reserveOnceOrThrow(operationId, userId, feature, input);
      return;
    }
    RLock lock = redissonClient.getLock(OP_RESERVE_LOCK_PREFIX + operationId);
    boolean locked = false;
    try {
      locked = lock.tryLock(
          RESERVE_LOCK_WAIT_SECONDS, RESERVE_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
      if (!locked) {
        throw new BusinessException(
            ErrorCode.INTERNAL_ERROR, "AI 配额预占繁忙，请稍后重试");
      }
      if (operationRepository.existsById(operationId)) {
        return;
      }
      reserveOnceOrThrow(operationId, userId, feature, input);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "AI 配额预占被中断，请稍后重试", e);
    } finally {
      if (locked) {
        try {
          lock.unlock();
        } catch (IllegalMonitorStateException e) {
          log.warn("[AiBudgetService] 释放预占锁失败，锁可能已过期: operationId={}", operationId, e);
        }
      }
    }
  }

  private void reserveOnceOrThrow(String operationId, Long userId, AiFeature feature,
      AiCostEstimateInput input) {
    BigDecimal estimated = estimator.estimate(feature, input);
    if (reserve(operationId, userId, feature, estimated) == BudgetDecision.HARD_LIMIT) {
      throw new AiBudgetExceededException(
          "本月 AI 额度已用完，" + resetHint() + "；额度恢复后可继续使用。");
    }
  }

  /**
   * 模型调用前的兜底预占：入口没有显式预占时，按默认估算补一次。
   * 已存在操作记录（说明入口已预占或已结算）时不再重复预占。
   */
  public void ensureReserved(String operationId, Long userId, AiFeature feature) {
    if (operationId == null || operationRepository.existsById(operationId)) {
      return;
    }
    reserveOrThrow(operationId, userId, feature,
        new AiCostEstimateInput(null, null, null, null, null));
  }

  @Override
  public void finalize(String operationId) {
    AiUsageOperationEntity op = operationRepository.findById(operationId).orElse(null);
    if (op == null || !AiOperationStatus.RESERVED.name().equals(op.getStatus())) {
      return;
    }
    RAtomicLong calls = redissonClient.getAtomicLong(OP_CALLS_PREFIX + operationId);
    RAtomicLong cost = redissonClient.getAtomicLong(OP_COST_PREFIX + operationId);
    long callCount = calls.get();
    long actualMicro = callCount > 0 ? cost.get() : 0L;
    if (Boolean.TRUE.equals(op.getExempt()) || op.getUserId() == null) {
      // 豁免/系统任务：只记录真实成本供审计，不进入总量池。
      op.setActualCost(fromMicro(actualMicro));
      op.setStatus(AiOperationStatus.SUCCEEDED.name());
    } else if (callCount > 0) {
      settleMonth(op.getUserId(), toMicro(op.getEstimatedCost()), actualMicro);
      op.setActualCost(fromMicro(actualMicro));
      op.setStatus(AiOperationStatus.SUCCEEDED.name());
    } else {
      releaseMonth(op.getUserId(), toMicro(op.getEstimatedCost()));
      op.setStatus(AiOperationStatus.RELEASED.name());
    }
    op.setSettledAt(Instant.now());
    operationRepository.save(op);
    calls.delete();
    cost.delete();
  }

  /** 单次模型调用结束后累加真实成本；是否入池由 {@link #finalize(String)} 决定。 */
  public void recordModelCall(String operationId, long costMicro) {
    if (operationId == null) {
      return;
    }
    try {
      RAtomicLong calls = redissonClient.getAtomicLong(OP_CALLS_PREFIX + operationId);
      calls.incrementAndGet();
      calls.expire(Duration.ofSeconds(MONTH_TTL_SECONDS));
      RAtomicLong cost = redissonClient.getAtomicLong(OP_COST_PREFIX + operationId);
      cost.addAndGet(costMicro);
      cost.expire(Duration.ofSeconds(MONTH_TTL_SECONDS));
    } catch (Exception e) {
      log.warn("[AiBudgetService] 记录操作真实费用失败: operationId={}", operationId, e);
    }
  }

  /** 当前自然月已用额度（元）。 */
  public BigDecimal usedCny(Long userId) {
    if (userId == null) {
      return BigDecimal.ZERO;
    }
    try {
      BigDecimal quota = monthlyQuotaCny(userId);
      if (quota != null && quota.signum() > 0) {
        seedMonthlyUsage(userId, quota);
      }
      Object value = redissonClient
          .getBucket(quotaKey("used", userId), StringCodec.INSTANCE)
          .get();
      if (value != null) {
        return fromMicro(Long.parseLong(value.toString()));
      }
      // 授权用户没有总量池键；免费用户 Redis 键缺失时回退到账本，避免“重启变 0”。
      return monthlyUsageService.currentMonthCny(userId);
    } catch (Exception e) {
      log.warn("[AiBudgetService] 读取本月已用额度失败: userId={}", userId, e);
      return BigDecimal.ZERO;
    }
  }

  /** 免费用户当前人均月额度；null 表示不限（授权/管理员/没有分摊对象）。 */
  public BigDecimal monthlyQuotaCny(Long userId) {
    if (userId == null || accessPolicy.isExempt(userId)) {
      return null;
    }
    return quotaConfigService.perUserCny();
  }

  /**
   * 月度累计百分比。已存在的进度不随总量或免费用户数变化；
   * 下一笔真实模型调用结算时，才按当时的人均额度继续累加。
   */
  public BigDecimal percentUsed(Long userId) {
    BigDecimal quota = monthlyQuotaCny(userId);
    if (quota == null || quota.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    seedMonthlyUsage(userId, quota);
    long quotaMicro = toMicro(quota);
    try {
      RBucket<String> bucket = redissonClient.getBucket(
          quotaKey("percent", userId), StringCodec.INSTANCE);
      String value = bucket.get();
      if (value == null) {
        long initial = toPercentMicro(toMicro(usedCny(userId)), quotaMicro);
        bucket.setIfAbsent(
            String.valueOf(initial), Duration.ofSeconds(MONTH_TTL_SECONDS));
        value = bucket.get();
      }
      long percentMicro = value == null ? 0L : Long.parseLong(value);
      return BigDecimal.valueOf(percentMicro)
          .divide(BigDecimal.valueOf(PERCENT_SCALE), 2, RoundingMode.HALF_UP);
    } catch (Exception e) {
      log.warn("[AiBudgetService] 读取月度累计百分比失败: userId={}", userId, e);
      return BigDecimal.ZERO;
    }
  }

  public String resetHint() {
    LocalDate next = YearMonth.now(ZONE).plusMonths(1).atDay(1);
    return next + " 0 点重置";
  }

  private int reserveMonth(Long userId, long estimateMicro, long hardMicro) {
    return (int) eval(reserveSha,
        List.of(quotaKey("used", userId), quotaKey("reserved", userId)),
        String.valueOf(estimateMicro), String.valueOf(hardMicro), String.valueOf(hardMicro),
        String.valueOf(MONTH_TTL_SECONDS));
  }

  private void settleMonth(Long userId, long estimated, long actual) {
    BigDecimal quota = quotaConfigService.perUserCny();
    long quotaMicro = quota == null ? 0L : toMicro(quota);
    long percentDelta = toPercentMicro(actual, quotaMicro);
    eval(settleSha,
        List.of(quotaKey("used", userId), quotaKey("reserved", userId),
            quotaKey("percent", userId)),
        String.valueOf(estimated), String.valueOf(actual), String.valueOf(MONTH_TTL_SECONDS),
        String.valueOf(quotaMicro), String.valueOf(percentDelta));
  }

  private void releaseMonth(Long userId, long estimated) {
    eval(releaseSha, List.of(quotaKey("reserved", userId)),
        String.valueOf(estimated), String.valueOf(MONTH_TTL_SECONDS));
  }

  /**
   * 当月首次进入时把账本历史并进 Redis。脚本按最大值合并，重复执行不会把同一笔成本加两次；
   * 写 settled 标记后，后续请求只读 Redis，不再在每次额度查询时扫描明细表。
   */
  private void seedMonthlyUsage(Long userId, BigDecimal quota) {
    if (userId == null || quota == null || quota.signum() <= 0) {
      return;
    }
    try {
      RBucket<String> seeded = redissonClient.getBucket(
          quotaKey("seeded", userId), StringCodec.INSTANCE);
      RBucket<String> used = redissonClient.getBucket(
          quotaKey("used", userId), StringCodec.INSTANCE);
      if (seeded.get() != null && used.get() != null) {
        return;
      }
      BigDecimal dbUsed = monthlyUsageService.currentMonthCny(userId);
      eval(seedSha,
          List.of(quotaKey("used", userId), quotaKey("percent", userId),
              quotaKey("seeded", userId)),
          String.valueOf(toMicro(dbUsed)), String.valueOf(toMicro(quota)),
          String.valueOf(MONTH_TTL_SECONDS), String.valueOf(PERCENT_SCALE));
    } catch (Exception e) {
      log.warn("[AiBudgetService] 合并当月历史额度失败: userId={}", userId, e);
    }
  }

  private long eval(String sha, List<Object> keys, String... args) {
    try {
      Object result = rScript.evalSha(RScript.Mode.READ_WRITE, sha, RScript.ReturnType.VALUE,
          keys, (Object[]) args);
      return result instanceof Number n ? n.longValue() : 0;
    } catch (org.redisson.client.RedisException e) {
      if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
        initQuietly();
        Object result = rScript.evalSha(RScript.Mode.READ_WRITE, sha, RScript.ReturnType.VALUE,
            keys, (Object[]) args);
        return result instanceof Number n ? n.longValue() : 0;
      }
      throw e;
    }
  }

  private void initQuietly() {
    try {
      init();
    } catch (Exception e) {
      log.warn("[AiBudgetService] 重新加载预算 Lua 脚本失败", e);
    }
  }

  private String quotaKey(String metric, Long userId) {
    return "ai:quota:" + YearMonth.now(ZONE).format(YEAR_MONTH) + ":" + userId + ":" + metric;
  }

  private static long toMicro(BigDecimal amount) {
    if (amount == null) {
      return 0;
    }
    return amount.multiply(MICRO).setScale(0, RoundingMode.HALF_UP).longValue();
  }

  static long toPercentMicro(long amountMicro, long quotaMicro) {
    if (amountMicro <= 0 || quotaMicro <= 0) {
      return 0L;
    }
    return BigDecimal.valueOf(amountMicro)
        .multiply(BigDecimal.valueOf(100))
        .multiply(BigDecimal.valueOf(PERCENT_SCALE))
        .divide(BigDecimal.valueOf(quotaMicro), 0, RoundingMode.HALF_UP)
        .longValue();
  }

  private static BigDecimal fromMicro(long micro) {
    return BigDecimal.valueOf(micro).divide(MICRO, 6, RoundingMode.HALF_UP);
  }

  private static BigDecimal scale(BigDecimal amount) {
    return amount.setScale(4, RoundingMode.HALF_UP);
  }

  private static String load(String path) throws Exception {
    return new String(new ClassPathResource(path).getContentAsByteArray(), StandardCharsets.UTF_8);
  }
}
