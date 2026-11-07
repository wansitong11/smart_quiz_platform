package interview.guide.modules.aicost.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.aicost.dto.AiUsageDtos.ErrorItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.EventItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.ModelOption;
import interview.guide.modules.aicost.dto.AiUsageDtos.PageResult;
import interview.guide.modules.aicost.dto.AiUsageDtos.PriceItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.PriceSyncResult;
import interview.guide.modules.aicost.dto.AiUsageDtos.PriceUpsertRequest;
import interview.guide.modules.aicost.dto.AiUsageDtos.QuotaConfigRequest;
import interview.guide.modules.aicost.dto.AiUsageDtos.QuotaConfigView;
import interview.guide.modules.aicost.dto.AiUsageDtos.QuotaUserItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.RankingItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.ReportSummary;
import interview.guide.modules.aicost.dto.AiUsageDtos.TrendPoint;
import interview.guide.modules.aicost.dto.AiUsageDtos.UserUsageItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.UserLimitsUpsertRequest;
import interview.guide.modules.aicost.dto.AiUsageDtos.UserLimitsView;
import interview.guide.modules.aicost.dto.AiUsageDtos.TierRequest;
import interview.guide.modules.aicost.service.AiCostReportService;
import interview.guide.modules.aicost.service.AiModelPriceAdminService;
import interview.guide.modules.aicost.service.AiModelPriceSyncService;
import interview.guide.modules.aicost.service.AiQuotaConfigService;
import interview.guide.modules.aicost.service.AiUserLimitAdminService;
import interview.guide.modules.aicost.service.ReportFilter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import interview.guide.modules.auth.security.CurrentUserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ai-cost")
@PreAuthorize("hasAuthority('ai:cost:view')")
@RequiredArgsConstructor
public class AiCostAdminController {

  private final AiCostReportService reportService;
  private final AiModelPriceAdminService priceAdminService;
  private final AiModelPriceSyncService priceSyncService;
  private final AiUserLimitAdminService userLimitAdminService;
  private final AiQuotaConfigService quotaConfigService;

  @GetMapping("/summary")
  public Result<ReportSummary> summary(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String provider,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String modality) {
    Range range = Range.of(from, to);
    return Result.success(reportService.summary(filter(range, provider, model, modality)));
  }

  @GetMapping("/trend")
  public Result<List<TrendPoint>> trend(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String provider,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String modality) {
    Range range = Range.of(from, to);
    return Result.success(reportService.trend(filter(range, provider, model, modality)));
  }

  @GetMapping("/ranking")
  public Result<List<RankingItem>> ranking(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "FEATURE") String dimension,
      @RequestParam(required = false) String provider,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String modality) {
    Range range = Range.of(from, to);
    return Result.success(
        reportService.ranking(filter(range, provider, model, modality), dimension));
  }

  @GetMapping("/users")
  public Result<PageResult<UserUsageItem>> users(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String provider,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String modality) {
    Range range = Range.of(from, to);
    return Result.success(
        reportService.users(filter(range, provider, model, modality), page, size));
  }

  @GetMapping("/errors")
  public Result<List<ErrorItem>> errors(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String provider,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String modality) {
    Range range = Range.of(from, to);
    return Result.success(reportService.errors(filter(range, provider, model, modality)));
  }

  @GetMapping("/events")
  public Result<PageResult<EventItem>> events(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String feature,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String provider,
      @RequestParam(required = false) String modality,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long userId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Range range = Range.of(from, to);
    return Result.success(reportService.events(
        filter(range, provider, model, modality), feature, status, userId, page, size));
  }

  @GetMapping("/models")
  public Result<List<ModelOption>> models(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to) {
    Range range = Range.of(from, to);
    return Result.success(reportService.models(range.from(), range.to()));
  }

  @GetMapping("/prices")
  public Result<List<PriceItem>> prices() {
    return Result.success(priceAdminService.list());
  }

  @PostMapping("/prices")
  public Result<PriceItem> upsertPrice(@RequestBody PriceUpsertRequest request) {
    return Result.success(priceAdminService.upsert(request));
  }

  @DeleteMapping("/prices/{id}")
  public Result<Void> deletePrice(@PathVariable Long id) {
    priceAdminService.delete(id);
    return Result.success();
  }

  @PostMapping("/prices/sync")
  public Result<PriceSyncResult> syncPrices() {
    return Result.success(priceSyncService.syncNow());
  }

  @GetMapping("/features")
  public Result<List<String>> features() {
    return Result.success(userLimitAdminService.features());
  }

  @GetMapping("/quota-config")
  public Result<QuotaConfigView> quotaConfig() {
    return Result.success(quotaConfigService.view());
  }

  @PutMapping("/quota-config")
  public Result<Void> updateQuotaConfig(@RequestBody QuotaConfigRequest request) {
    quotaConfigService.updateMonthlyTotalCny(request.monthlyTotalCny(),
        CurrentUserHolder.currentUserId());
    return Result.success();
  }

  @PutMapping("/user-tiers/{userId}")
  public Result<Void> updateUserTier(@PathVariable Long userId,
      @RequestBody TierRequest request) {
    userLimitAdminService.updateTier(userId, request.tier(), CurrentUserHolder.currentUserId());
    return Result.success();
  }

  @GetMapping("/quota-users")
  public Result<List<QuotaUserItem>> quotaUsers() {
    return Result.success(userLimitAdminService.users());
  }

  @GetMapping("/user-limits/{userId}")
  public Result<List<UserLimitsView>> userLimits(@PathVariable Long userId) {
    return Result.success(userLimitAdminService.get(userId));
  }

  @PutMapping("/user-limits/{userId}")
  public Result<Void> upsertUserLimits(@PathVariable Long userId,
      @RequestBody UserLimitsUpsertRequest request) {
    userLimitAdminService.upsert(userId, request);
    return Result.success();
  }

  @DeleteMapping("/user-limits/{userId}")
  public Result<Void> clearUserLimits(@PathVariable Long userId,
      @RequestParam(required = false) String feature) {
    userLimitAdminService.clear(userId, feature);
    return Result.success();
  }

  private record Range(Instant from, Instant to) {
    static Range of(Instant from, Instant to) {
      Instant end = to == null ? Instant.now() : to;
      Instant start = from == null ? end.minus(7, ChronoUnit.DAYS) : from;
      return new Range(start, end);
    }
  }

  private static ReportFilter filter(
      Range range, String provider, String model, String modality) {
    return new ReportFilter(range.from(), range.to(), provider, model, modality);
  }
}
