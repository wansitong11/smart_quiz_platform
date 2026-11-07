package interview.guide.modules.aicost.service;

import interview.guide.common.ai.AiFeature;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aicost.AiCostProperties;
import interview.guide.modules.aicost.AiCostProperties.Default;
import interview.guide.modules.aicost.AiCostProperties.FeatureBudget;
import interview.guide.modules.aicost.dto.AiUsageDtos.EffectiveLimitsView;
import interview.guide.modules.aicost.dto.AiUsageDtos.QuotaUserItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.UserLimitsUpsertRequest;
import interview.guide.modules.aicost.dto.AiUsageDtos.UserLimitsView;
import interview.guide.modules.aicost.model.AiUserRateLimitOverrideEntity;
import interview.guide.modules.aicost.repository.AiUserRateLimitOverrideRepository;
import interview.guide.modules.auth.model.RoleCode;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserTier;
import interview.guide.modules.auth.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 管理员维护单个用户的限流覆盖与用户档位。
 *
 * <p>成本额度已经统一到月度总量池，本服务只保留 RPM / TPM / 并发三项限流配置。
 * 菜单里不再提供按功能的日/月成本上限。</p>
 */
@Service
@RequiredArgsConstructor
public class AiUserLimitAdminService {

  private static final List<AiFeature> FEATURES = Arrays.stream(AiFeature.values())
      .filter(feature -> feature != AiFeature.UNKNOWN)
      .toList();

  private final UserRepository userRepository;
  private final AiAdminPolicy adminPolicy;
  private final AiUserRateLimitOverrideRepository rateOverrideRepository;
  private final AiUserRateLimitResolver rateLimitResolver;
  private final AiCostProperties properties;
  private final UserAccessPolicy accessPolicy;
  private final AiQuotaConfigService quotaConfigService;

  @Transactional(readOnly = true)
  public List<UserLimitsView> get(Long userId) {
    requireEditableUser(userId);
    Instant now = Instant.now();
    List<AiUserRateLimitOverrideEntity> rateOverrides =
        rateOverrideRepository.findActiveByUser(userId, now);

    List<UserLimitsView> views = new ArrayList<>();
    views.add(buildView(null, rateOverrides));
    for (AiFeature feature : FEATURES) {
      views.add(buildView(feature.name(), rateOverrides));
    }
    return views;
  }

  public List<String> features() {
    return FEATURES.stream().map(AiFeature::name).toList();
  }

  @Transactional(readOnly = true)
  public List<QuotaUserItem> users() {
    Instant now = Instant.now();
    var rateByUser = rateOverrideRepository.findAllActive(now).stream()
        .collect(Collectors.groupingBy(AiUserRateLimitOverrideEntity::getUserId));
    return userRepository.findAllWithRoles().stream()
        .filter(user -> user.getRoles().stream()
            .noneMatch(role -> RoleCode.ADMIN.code().equals(role.getCode())))
        .map(user -> toQuotaUser(
            user, rateByUser.getOrDefault(user.getId(), List.of())))
        .toList();
  }

  @Transactional
  public void upsert(Long userId, UserLimitsUpsertRequest request) {
    requireEditableUser(userId);
    String feature = normalizeFeature(request.feature());
    applyRate(userId, feature, request.rpmLimit(), request.tpmLimit(),
        request.concurrencyLimit(), Instant.now());
    invalidateRateCacheAfterCommit(userId);
  }

  @Transactional
  public void clear(Long userId, String feature) {
    requireEditableUser(userId);
    String normalized = normalizeFeature(feature);
    Instant now = Instant.now();
    List<AiUserRateLimitOverrideEntity> rateOverrides = normalized == null
        ? rateOverrideRepository.findActiveGlobal(userId, now)
        : rateOverrideRepository.findActiveFeature(userId, normalized, now);
    rateOverrides.forEach(override -> override.setEffectiveTo(now));
    rateOverrideRepository.saveAll(rateOverrides);
    invalidateRateCacheAfterCommit(userId);
  }

  /** 管理员手动设置用户档位：FREE 占池、AUTHORIZED 豁免。 */
  @Transactional
  public void updateTier(Long userId, String tier, Long adminId) {
    requireEditableUser(userId);
    UserTier value;
    try {
      value = UserTier.valueOf(tier == null ? "" : tier.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "未知用户档位: " + tier);
    }
    UserEntity user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    user.setTier(value);
    user.setUpdatedAt(java.time.LocalDateTime.now());
    userRepository.save(user);
    invalidateAfterCommit(userId);
  }

  private void invalidateAfterCommit(Long userId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      accessPolicy.invalidate(userId);
      quotaConfigService.invalidate();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        accessPolicy.invalidate(userId);
        quotaConfigService.invalidate();
      }
    });
  }

  private void invalidateRateCacheAfterCommit(Long userId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      rateLimitResolver.invalidate(userId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        rateLimitResolver.invalidate(userId);
      }
    });
  }

  private void applyRate(Long userId, String feature, Integer rpm, Long tpm,
      Integer concurrency, Instant now) {
    List<AiUserRateLimitOverrideEntity> active = feature == null
        ? rateOverrideRepository.findActiveGlobal(userId, now)
        : rateOverrideRepository.findActiveFeature(userId, feature, now);
    active.forEach(override -> override.setEffectiveTo(now));
    rateOverrideRepository.saveAll(active);
    if (rpm != null || tpm != null || concurrency != null) {
      rateOverrideRepository.save(AiUserRateLimitOverrideEntity.builder()
          .userId(userId)
          .feature(feature)
          .rpmLimit(rpm)
          .tpmLimit(tpm)
          .concurrencyLimit(concurrency)
          .effectiveFrom(now)
          .build());
    }
  }

  private UserLimitsView buildView(String feature,
      List<AiUserRateLimitOverrideEntity> rateOverrides) {
    AiUserRateLimitOverrideEntity rate = rateOverrides.stream()
        .filter(override -> feature == null
            ? override.getFeature() == null
            : feature.equals(override.getFeature()))
        .findFirst()
        .orElse(null);
    return new UserLimitsView(
        feature,
        rate == null ? null : rate.getRpmLimit(),
        rate == null ? null : rate.getTpmLimit(),
        rate == null ? null : rate.getConcurrencyLimit(),
        effectiveLimits(feature, rateOverrides));
  }

  private QuotaUserItem toQuotaUser(
      UserEntity user,
      List<AiUserRateLimitOverrideEntity> rateOverrides) {
    int featureOverrideCount = (int) rateOverrides.stream()
        .map(AiUserRateLimitOverrideEntity::getFeature)
        .filter(Objects::nonNull)
        .distinct()
        .count();
    return new QuotaUserItem(
        user.getId(),
        user.getUsername(),
        user.getDisplayName(),
        user.getStatus().name(),
        user.getRoles().stream().map(role -> role.getCode()).sorted().toList(),
        user.getTier().name(),
        effectiveLimits(null, rateOverrides),
        featureOverrideCount);
  }

  private EffectiveLimitsView effectiveLimits(
      String feature,
      List<AiUserRateLimitOverrideEntity> rateOverrides) {
    Default defaults = properties.getBudget().getDefaultRules();
    FeatureBudget featureBudget = feature == null
        ? null
        : properties.getBudget().getFeatures().get(feature);

    int rpm = featureBudget != null && featureBudget.getRpm() != null
        ? featureBudget.getRpm()
        : defaults.getRpm();
    long tpm = featureBudget != null && featureBudget.getTpm() != null
        ? featureBudget.getTpm()
        : defaults.getTpm();
    int concurrency = featureBudget != null && featureBudget.getConcurrency() != null
        ? featureBudget.getConcurrency()
        : defaults.getConcurrency();

    AiUserRateLimitOverrideEntity globalRate = findRateOverride(rateOverrides, null);
    AiUserRateLimitOverrideEntity featureRate = feature == null
        ? null
        : findRateOverride(rateOverrides, feature);
    if (globalRate != null) {
      rpm = globalRate.getRpmLimit() != null ? globalRate.getRpmLimit() : rpm;
      tpm = globalRate.getTpmLimit() != null ? globalRate.getTpmLimit() : tpm;
      concurrency = globalRate.getConcurrencyLimit() != null
          ? globalRate.getConcurrencyLimit()
          : concurrency;
    }
    if (featureRate != null) {
      rpm = featureRate.getRpmLimit() != null ? featureRate.getRpmLimit() : rpm;
      tpm = featureRate.getTpmLimit() != null ? featureRate.getTpmLimit() : tpm;
      concurrency = featureRate.getConcurrencyLimit() != null
          ? featureRate.getConcurrencyLimit()
          : concurrency;
    }

    return new EffectiveLimitsView(rpm, tpm, concurrency);
  }

  private AiUserRateLimitOverrideEntity findRateOverride(
      List<AiUserRateLimitOverrideEntity> overrides,
      String feature) {
    return overrides.stream()
        .filter(override -> feature == null
            ? override.getFeature() == null
            : feature.equals(override.getFeature()))
        .findFirst()
        .orElse(null);
  }

  private void requireEditableUser(Long userId) {
    if (userId == null || !userRepository.existsById(userId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
    }
    if (adminPolicy.isAdmin(userId)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "管理员账号不受限流/成本限制，无需配置");
    }
  }

  private String normalizeFeature(String feature) {
    if (feature == null || feature.isBlank()) {
      return null;
    }
    try {
      return AiFeature.valueOf(feature.toUpperCase()).name();
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "未知 AI 功能: " + feature);
    }
  }
}
