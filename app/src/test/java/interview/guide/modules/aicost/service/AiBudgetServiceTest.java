package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiFeature;
import interview.guide.modules.aicost.AiOperationStatus;
import interview.guide.modules.aicost.model.AiUsageOperationEntity;
import interview.guide.modules.aicost.repository.AiUsageOperationRepository;
import interview.guide.modules.aicost.service.AiBudgetService.BudgetDecision;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("用户月度额度预算")
class AiBudgetServiceTest {

  private static final Long USER_ID = 7L;
  private static final String OPERATION_ID = "operation-1";

  @Mock
  private RedissonClient redissonClient;

  @Mock
  private AiUsageOperationRepository operationRepository;

  @Mock
  private AiCostEstimator estimator;

  @Mock
  private UserAccessPolicy accessPolicy;

  @Mock
  private AiQuotaConfigService quotaConfigService;

  @Mock
  private AiMonthlyUsageService monthlyUsageService;

  @Mock
  private RScript rScript;

  @Mock
  private RLock reserveLock;

  @Mock
  private RBucket<String> usedBucket;

  @Mock
  private RBucket<String> percentBucket;

  @Mock
  private RBucket<String> seededBucket;

  private AiBudgetService budgetService;

  @BeforeEach
  void setUp() {
    budgetService = new AiBudgetService(redissonClient, operationRepository, estimator,
        accessPolicy, quotaConfigService, monthlyUsageService);
    lenient().doReturn(seededBucket).when(redissonClient).getBucket(
        argThat((String key) -> key.endsWith(":seeded")), eq(StringCodec.INSTANCE));
    lenient().doReturn(usedBucket).when(redissonClient).getBucket(
        argThat((String key) -> key.endsWith(":used")), eq(StringCodec.INSTANCE));
    ReflectionTestUtils.setField(budgetService, "rScript", rScript);
    ReflectionTestUtils.setField(budgetService, "reserveSha", "reserve-sha");
    ReflectionTestUtils.setField(budgetService, "settleSha", "settle-sha");
    ReflectionTestUtils.setField(budgetService, "releaseSha", "release-sha");
    ReflectionTestUtils.setField(budgetService, "seedSha", "seed-sha");
  }

  @Test
  @DisplayName("授权用户豁免额度但仍记录操作")
  void shouldBypassQuotaForExemptUser() {
    when(accessPolicy.isExempt(USER_ID)).thenReturn(true);

    BudgetDecision decision = budgetService.reserve(
        OPERATION_ID, USER_ID, AiFeature.PAPER_IMPORT, new BigDecimal("0.01"));

    assertThat(decision).isEqualTo(BudgetDecision.PASS);
    verifyNoInteractions(redissonClient, quotaConfigService);
    ArgumentCaptor<AiUsageOperationEntity> captor =
        ArgumentCaptor.forClass(AiUsageOperationEntity.class);
    verify(operationRepository).save(captor.capture());
    assertThat(captor.getValue().getOperationId()).isEqualTo(OPERATION_ID);
    assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
    assertThat(captor.getValue().getStatus()).isEqualTo(AiOperationStatus.RESERVED.name());
    assertThat(captor.getValue().getExempt()).isTrue();
    assertThat(captor.getValue().getCurrency()).isEqualTo("CNY");
  }

  @Test
  @DisplayName("免费用户超过当前人均额度时拒绝，且不写预占")
  void shouldRejectWhenOverQuota() {
    when(accessPolicy.isExempt(USER_ID)).thenReturn(false);
    when(quotaConfigService.perUserCny()).thenReturn(new BigDecimal("2.00"));
    when(rScript.evalSha(
        eq(RScript.Mode.READ_WRITE),
        eq("reserve-sha"),
        eq(RScript.ReturnType.VALUE),
        anyList(),
        any(Object[].class))).thenReturn(-1L);

    BudgetDecision decision = budgetService.reserve(
        OPERATION_ID, USER_ID, AiFeature.PAPER_IMPORT, new BigDecimal("0.01"));

    assertThat(decision).isEqualTo(BudgetDecision.HARD_LIMIT);
    ArgumentCaptor<AiUsageOperationEntity> captor =
        ArgumentCaptor.forClass(AiUsageOperationEntity.class);
    verify(operationRepository, times(2)).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(AiOperationStatus.RELEASED.name());
    assertThat(captor.getValue().getSettledAt()).isNotNull();
  }

  @Test
  @DisplayName("未超额时正常预占")
  void shouldReserveWhenUnderQuota() {
    when(accessPolicy.isExempt(USER_ID)).thenReturn(false);
    when(quotaConfigService.perUserCny()).thenReturn(new BigDecimal("2.00"));
    when(rScript.evalSha(
        eq(RScript.Mode.READ_WRITE),
        eq("reserve-sha"),
        eq(RScript.ReturnType.VALUE),
        anyList(),
        any(Object[].class))).thenReturn(0L);

    BudgetDecision decision = budgetService.reserve(
        OPERATION_ID, USER_ID, AiFeature.PAPER_IMPORT, new BigDecimal("0.01"));

    assertThat(decision).isEqualTo(BudgetDecision.PASS);
    verify(operationRepository).save(any());
  }

  @Test
  @DisplayName("人均额度直接取总量池服务的结果")
  void shouldUsePerUserQuotaFromPool() {
    when(accessPolicy.isExempt(USER_ID)).thenReturn(false);
    when(quotaConfigService.perUserCny()).thenReturn(new BigDecimal("2.000000"));

    assertThat(budgetService.monthlyQuotaCny(USER_ID)).isEqualByComparingTo("2.000000");
  }

  @Test
  @DisplayName("授权用户读取额度返回不限")
  void shouldReturnUnlimitedForExemptUser() {
    when(accessPolicy.isExempt(USER_ID)).thenReturn(true);

    assertThat(budgetService.monthlyQuotaCny(USER_ID)).isNull();
    verify(quotaConfigService, never()).perUserCny();
  }

  @Test
  @DisplayName("同一操作号重复预占时只保留一次预占")
  void shouldTreatReserveAsIdempotent() throws InterruptedException {
    when(redissonClient.getLock("ai:op:reserve:lock:" + OPERATION_ID))
        .thenReturn(reserveLock);
    when(reserveLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
    when(operationRepository.existsById(OPERATION_ID)).thenReturn(true);

    budgetService.reserveOrThrow(
        OPERATION_ID, USER_ID, AiFeature.PAPER_IMPORT,
        new interview.guide.common.ai.AiCostEstimateInput(1L, null, null, null, null));

    verify(estimator, never()).estimate(any(), any());
    verify(operationRepository, never()).save(any());
    verify(reserveLock).unlock();
  }

  @Test
  @DisplayName("首次读取百分比时按当前已用额度初始化一次")
  void shouldInitializePercentFromCurrentUsage() {
    when(accessPolicy.isExempt(USER_ID)).thenReturn(false);
    when(quotaConfigService.perUserCny()).thenReturn(new BigDecimal("2.00"));
    doReturn(usedBucket).when(redissonClient).getBucket(
        argThat((String key) -> key.endsWith(":used")), eq(StringCodec.INSTANCE));
    doReturn(percentBucket).when(redissonClient).getBucket(
        argThat((String key) -> key.endsWith(":percent")), eq(StringCodec.INSTANCE));
    when(usedBucket.get()).thenReturn("700000");
    when(percentBucket.get()).thenReturn(null, "35000000");
    when(percentBucket.setIfAbsent(anyString(), any(Duration.class)))
        .thenReturn(true);

    BigDecimal percent = budgetService.percentUsed(USER_ID);

    assertThat(percent).isEqualByComparingTo("35");
    verify(percentBucket).setIfAbsent(
        eq("35000000"), any(Duration.class));
  }

  @Test
  @DisplayName("首次读取时把当月账本历史补进预占基线")
  void shouldSeedMonthlyHistoryBeforeQuotaCheck() {
    when(accessPolicy.isExempt(USER_ID)).thenReturn(false);
    when(quotaConfigService.perUserCny()).thenReturn(new BigDecimal("2.00"));
    when(seededBucket.get()).thenReturn(null);
    when(monthlyUsageService.currentMonthCny(USER_ID)).thenReturn(new BigDecimal("0.70"));
    doReturn(percentBucket).when(redissonClient).getBucket(
        argThat((String key) -> key.endsWith(":percent")), eq(StringCodec.INSTANCE));
    when(percentBucket.get()).thenReturn("35000000");

    budgetService.percentUsed(USER_ID);

    verify(monthlyUsageService).currentMonthCny(USER_ID);
    ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(rScript).evalSha(
        eq(RScript.Mode.READ_WRITE),
        eq("seed-sha"),
        eq(RScript.ReturnType.VALUE),
        anyList(),
        argsCaptor.capture());
    assertThat(argsCaptor.getValue())
        .containsExactly("700000", "2000000", "3888000", "1000000");
  }

  @Test
  @DisplayName("调整人均额度后已有百分比保持不变")
  void shouldKeepExistingPercentWhenQuotaChanges() {
    when(accessPolicy.isExempt(USER_ID)).thenReturn(false);
    when(quotaConfigService.perUserCny()).thenReturn(new BigDecimal("4.00"));
    doReturn(percentBucket).when(redissonClient).getBucket(
        argThat((String key) -> key.endsWith(":percent")), eq(StringCodec.INSTANCE));
    when(percentBucket.get()).thenReturn("35000000");

    BigDecimal percent = budgetService.percentUsed(USER_ID);

    assertThat(percent).isEqualByComparingTo("35");
    verify(percentBucket, never()).setIfAbsent(anyString(), any(Duration.class));
  }

  @Test
  @DisplayName("百分比保留两位小数")
  void shouldKeepFractionalPercent() {
    when(accessPolicy.isExempt(USER_ID)).thenReturn(false);
    when(quotaConfigService.perUserCny()).thenReturn(new BigDecimal("2.00"));
    doReturn(percentBucket).when(redissonClient).getBucket(
        argThat((String key) -> key.endsWith(":percent")), eq(StringCodec.INSTANCE));
    when(percentBucket.get()).thenReturn("160000");

    assertThat(budgetService.percentUsed(USER_ID)).isEqualByComparingTo("0.16");
  }

  @Test
  @DisplayName("百分比增量跟随当次调用时的人均额度")
  void shouldConvertCostToPercentUsingCurrentQuota() {
    assertThat(AiBudgetService.toPercentMicro(700_000L, 2_000_000L))
        .isEqualTo(35_000_000L);
    assertThat(AiBudgetService.toPercentMicro(100_000L, 4_000_000L))
        .isEqualTo(2_500_000L);
  }
}
