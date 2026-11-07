package interview.guide.modules.aicost.service;

import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.modules.aicost.dto.AiUsageDtos.EstimateRequest;
import interview.guide.modules.aicost.dto.AiUsageDtos.EstimateResult;
import interview.guide.modules.aicost.dto.AiUsageDtos.FeatureUsage;
import interview.guide.modules.aicost.dto.AiUsageDtos.QuotaView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiUsageEstimateService {

  private static final List<AiFeature> MONITORED = List.of(
      AiFeature.PAPER_IMPORT,
      AiFeature.PAPER_RECOGNIZE,
      AiFeature.QUIZ_GENERATE,
      AiFeature.KB_QA,
      AiFeature.KB_VECTORIZE,
      AiFeature.ANSWER_EVALUATE
  );

  private final AiCostEstimator estimator;
  private final AiBudgetService budgetService;
  private final UserAccessPolicy accessPolicy;

  public EstimateResult estimate(Long userId, EstimateRequest request) {
    AiFeature feature = parseFeature(request.feature());
    AiCostEstimateInput input = new AiCostEstimateInput(
        request.pageCount(), request.questionCount(), request.contextTokens(),
        request.chunkCount(), request.outputTokens());
    BigDecimal estimated = estimator.estimate(feature, input);
    BigDecimal quota = budgetService.monthlyQuotaCny(userId);
    BigDecimal used = budgetService.usedCny(userId);
    BigDecimal remaining = quota == null
        ? BigDecimal.ZERO
        : quota.subtract(used).max(BigDecimal.ZERO);
    long remainingOps = estimated.signum() > 0
        ? remaining.divide(estimated, 0, RoundingMode.FLOOR).longValue() : 0;
    return new EstimateResult(scale(estimated), scale(remaining), remainingOps,
        quota == null ? BigDecimal.ZERO : scale(quota), scale(used));
  }

  /** 当前用户额度：只返回月度累计百分比；授权与管理员返回不限额度。 */
  public QuotaView quota(Long userId) {
    String tier = accessPolicy.tier(userId).name();
    if (accessPolicy.isExempt(userId)) {
      return new QuotaView(tier, true, BigDecimal.ZERO);
    }
    BigDecimal quota = budgetService.monthlyQuotaCny(userId);
    if (quota == null) {
      return new QuotaView(tier, true, BigDecimal.ZERO);
    }
    BigDecimal percent = budgetService.percentUsed(userId)
        .max(BigDecimal.ZERO)
        .min(BigDecimal.valueOf(100));
    return new QuotaView(tier, false, percent);
  }

  /**
   * 兼容旧接口：成本口径已统一为用户总额度，所有功能共享同一份额度，
   * 因此这里只返回一条 TOTAL 记录，保留给旧前端/脚本读取。
   */
  public List<FeatureUsage> summary(Long userId) {
    BigDecimal quota = budgetService.monthlyQuotaCny(userId);
    BigDecimal used = budgetService.usedCny(userId);
    if (quota == null) {
      return List.of(new FeatureUsage("TOTAL", scale(used), BigDecimal.ZERO, BigDecimal.ZERO,
          scale(used), BigDecimal.ZERO, 0));
    }
    return List.of(new FeatureUsage("TOTAL", scale(used), BigDecimal.ZERO, scale(quota),
        scale(used), scale(quota), 0));
  }

  private AiFeature parseFeature(String feature) {
    if (feature == null) {
      return AiFeature.UNKNOWN;
    }
    try {
      return AiFeature.valueOf(feature.toUpperCase());
    } catch (IllegalArgumentException e) {
      return AiFeature.UNKNOWN;
    }
  }

  private BigDecimal scale(BigDecimal amount) {
    return amount.setScale(6, RoundingMode.HALF_UP);
  }
}
