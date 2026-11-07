package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.modules.aicost.dto.AiUsageDtos.EstimateRequest;
import interview.guide.modules.aicost.dto.AiUsageDtos.EstimateResult;
import interview.guide.modules.aicost.dto.AiUsageDtos.QuotaView;
import interview.guide.modules.auth.model.UserTier;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiUsageEstimateServiceTest {

  @Mock
  private AiCostEstimator estimator;

  @Mock
  private AiBudgetService budgetService;

  @Mock
  private UserAccessPolicy accessPolicy;

  @Test
  @DisplayName("按成本加权向下取整剩余次数")
  void estimateRemainingOpsByCost() {
    when(estimator.estimate(eq(AiFeature.PAPER_IMPORT), any(AiCostEstimateInput.class)))
        .thenReturn(new BigDecimal("0.10"));
    when(budgetService.monthlyQuotaCny(1L)).thenReturn(new BigDecimal("2.00"));
    when(budgetService.usedCny(1L)).thenReturn(new BigDecimal("1.70"));

    AiUsageEstimateService service =
        new AiUsageEstimateService(estimator, budgetService, accessPolicy);
    EstimateResult result = service.estimate(1L,
        new EstimateRequest("PAPER_IMPORT", 2L, null, null, null, null));

    assertThat(result.estimatedRemainingOps()).isEqualTo(3);
    assertThat(result.remainingCny()).isEqualByComparingTo("0.30");
  }

  @Test
  @DisplayName("剩余为 0 时不给出负数次数")
  void zeroRemainingOpsWhenExhausted() {
    when(estimator.estimate(eq(AiFeature.PAPER_IMPORT), any(AiCostEstimateInput.class)))
        .thenReturn(new BigDecimal("0.10"));
    when(budgetService.monthlyQuotaCny(1L)).thenReturn(new BigDecimal("2.00"));
    when(budgetService.usedCny(1L)).thenReturn(new BigDecimal("2.60"));

    AiUsageEstimateService service =
        new AiUsageEstimateService(estimator, budgetService, accessPolicy);
    EstimateResult result = service.estimate(1L,
        new EstimateRequest("PAPER_IMPORT", 2L, null, null, null, null));

    assertThat(result.estimatedRemainingOps()).isZero();
    assertThat(result.remainingCny()).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("授权用户额度返回不限")
  void quotaUnlimitedForExemptUser() {
    when(accessPolicy.tier(1L)).thenReturn(UserTier.AUTHORIZED);
    when(accessPolicy.isExempt(1L)).thenReturn(true);

    AiUsageEstimateService service =
        new AiUsageEstimateService(estimator, budgetService, accessPolicy);
    QuotaView view = service.quota(1L);

    assertThat(view.unlimited()).isTrue();
    assertThat(view.percent()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("免费用户返回月度累计百分比")
  void quotaPercentForFreeUser() {
    when(accessPolicy.tier(1L)).thenReturn(UserTier.FREE);
    when(accessPolicy.isExempt(1L)).thenReturn(false);
    when(budgetService.monthlyQuotaCny(1L)).thenReturn(new BigDecimal("2.00"));
    when(budgetService.percentUsed(1L)).thenReturn(new BigDecimal("47"));

    AiUsageEstimateService service =
        new AiUsageEstimateService(estimator, budgetService, accessPolicy);
    QuotaView view = service.quota(1L);

    assertThat(view.unlimited()).isFalse();
    assertThat(view.percent()).isEqualByComparingTo("47");
  }

  @Test
  @DisplayName("小额调用也返回小数百分比")
  void quotaPercentKeepsFractionForSmallUsage() {
    when(accessPolicy.tier(1L)).thenReturn(UserTier.FREE);
    when(accessPolicy.isExempt(1L)).thenReturn(false);
    when(budgetService.monthlyQuotaCny(1L)).thenReturn(new BigDecimal("2.00"));
    when(budgetService.percentUsed(1L)).thenReturn(new BigDecimal("0.16"));

    AiUsageEstimateService service =
        new AiUsageEstimateService(estimator, budgetService, accessPolicy);
    QuotaView view = service.quota(1L);

    assertThat(view.percent()).isEqualByComparingTo("0.16");
  }
}
