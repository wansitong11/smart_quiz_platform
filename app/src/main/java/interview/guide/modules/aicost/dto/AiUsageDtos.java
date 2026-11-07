package interview.guide.modules.aicost.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AiUsageDtos {

  private AiUsageDtos() {
  }

  public record EstimateRequest(
      String feature,
      Long pageCount,
      Long questionCount,
      Long contextTokens,
      Long chunkCount,
      Long outputTokens
  ) {
  }

  public record EstimateResult(
      BigDecimal estimatedCny,
      BigDecimal remainingCny,
      long estimatedRemainingOps,
      BigDecimal quotaCny,
      BigDecimal usedCny
  ) {
  }

  /** 当前用户可见的额度视图：授权/管理员返回 unlimited=true。 */
  public record QuotaView(
      String tier,
      boolean unlimited,
      BigDecimal percent
  ) {
  }

  /** 管理端月度总量池视图。 */
  public record QuotaConfigView(
      BigDecimal monthlyTotalCny,
      long freeUserCount,
      BigDecimal perUserCny
  ) {
  }

  public record QuotaConfigRequest(
      BigDecimal monthlyTotalCny
  ) {
  }

  public record TierRequest(
      String tier
  ) {
  }

  public record FeatureUsage(
      String feature,
      BigDecimal dayUsed,
      BigDecimal daySoft,
      BigDecimal dayHard,
      BigDecimal monthUsed,
      BigDecimal monthHard,
      long estimatedRemainingOps
  ) {
  }

  public record ReportSummary(
      long totalCalls,
      BigDecimal estimatedCost,
      BigDecimal failureRate,
      BigDecimal p95LatencyMs,
      long inputTokens,
      long outputTokens,
      long budgetExceeded
  ) {
  }

  public record TrendPoint(
      Instant date,
      long calls,
      BigDecimal cost,
      BigDecimal failureRate
  ) {
  }

  public record RankingItem(
      String key,
      long calls,
      BigDecimal cost,
      long inputTokens,
      long outputTokens
  ) {
  }

  public record UserUsageItem(
      Long userId,
      String userName,
      long calls,
      long inputTokens,
      long outputTokens,
      BigDecimal cost,
      BigDecimal successRate
  ) {
  }

  public record ErrorItem(
      String key,
      long count
  ) {
  }

  public record EventItem(
      Long id,
      Instant occurredAt,
      Long userId,
      String userName,
      String feature,
      String provider,
      String model,
      String modality,
      String status,
      Long latencyMs,
      Long inputTokens,
      Long outputTokens,
      BigDecimal estimatedCost
  ) {
  }

  public record ModelOption(
      String provider,
      String model,
      String modality
  ) {
  }

  public record PageResult<T>(
      java.util.List<T> items,
      long total
  ) {
  }

  public record PriceUpsertRequest(
      Long id,
      String provider,
      String model,
      String modality,
      BigDecimal inputPricePer1m,
      BigDecimal outputPricePer1m,
      BigDecimal cachedPricePer1m
  ) {
  }

  public record PriceItem(
      Long id,
      String provider,
      String model,
      String modality,
      BigDecimal inputPricePer1m,
      BigDecimal outputPricePer1m,
      BigDecimal cachedPricePer1m,
      String source,
      Instant effectiveFrom,
      Instant effectiveTo
  ) {
  }

  public record UserLimitsUpsertRequest(
      String feature,
      Integer rpmLimit,
      Long tpmLimit,
      Integer concurrencyLimit
  ) {
  }

  public record UserLimitsView(
      String feature,
      Integer rpmLimit,
      Long tpmLimit,
      Integer concurrencyLimit,
      EffectiveLimitsView effective
  ) {
  }

  public record EffectiveLimitsView(
      Integer rpmLimit,
      Long tpmLimit,
      Integer concurrencyLimit
  ) {
  }

  public record QuotaUserItem(
      Long id,
      String username,
      String displayName,
      String status,
      List<String> roles,
      String tier,
      EffectiveLimitsView effective,
      int featureOverrideCount
  ) {
  }

  public record PriceSyncResult(
      int applied,
      String source
  ) {
  }
}
