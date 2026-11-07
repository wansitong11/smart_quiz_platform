package interview.guide.modules.aicost.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aicost.AiCostProperties;
import interview.guide.modules.aicost.dto.AiUsageDtos.QuotaConfigView;
import interview.guide.modules.aicost.model.AiQuotaConfigEntity;
import interview.guide.modules.aicost.repository.AiQuotaConfigRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import interview.guide.modules.auth.model.RoleCode;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.model.UserTier;
import interview.guide.modules.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 月度总量池配置：单行持久化，管理员可随时修改。
 */
@Service
@RequiredArgsConstructor
public class AiQuotaConfigService {

  private static final long CONFIG_ID = 1L;

  private final AiQuotaConfigRepository repository;
  private final AiCostProperties properties;
  private final UserRepository userRepository;

  private volatile CacheEntry cache;

  private record CacheEntry(BigDecimal totalCny, long freeUserCount, long expiresAtNanos) {
  }

  @Transactional(readOnly = true)
  public BigDecimal monthlyTotalCny() {
    return repository.findById(CONFIG_ID)
        .map(AiQuotaConfigEntity::getMonthlyTotalCny)
        .filter(value -> value != null && value.signum() > 0)
        .orElseGet(() -> properties.getQuota().getMonthlyTotalCny());
  }

  @Transactional
  public void updateMonthlyTotalCny(BigDecimal cny, Long adminId) {
    if (cny == null || cny.signum() <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "月度总额度必须大于 0");
    }
    AiQuotaConfigEntity entity = repository.findById(CONFIG_ID)
        .orElseGet(() -> AiQuotaConfigEntity.builder().id(CONFIG_ID).build());
    entity.setMonthlyTotalCny(cny);
    entity.setUpdatedBy(adminId);
    entity.setUpdatedAt(Instant.now());
    repository.save(entity);
    cache = null;
  }

  /** 参与分摊的免费用户数（不含管理员、不含授权用户）。 */
  public long freeUserCount() {
    return userRepository.countByTierAndStatusExcludingRole(
        UserTier.FREE, UserStatus.ACTIVE, RoleCode.ADMIN.code());
  }

  /** 用户档位或人数变化后立即失效缓存，避免最长 5 秒仍按旧人均额度判断。 */
  public void invalidate() {
    cache = null;
  }

  /** 当前人均月额度；没有分摊对象时返回 null 表示不限。 */
  public BigDecimal perUserCny() {
    CacheEntry snapshot = snapshot();
    if (snapshot.freeUserCount() <= 0) {
      return null;
    }
    return snapshot.totalCny()
        .divide(BigDecimal.valueOf(snapshot.freeUserCount()), 6, RoundingMode.HALF_UP);
  }

  public QuotaConfigView view() {
    CacheEntry snapshot = snapshot();
    BigDecimal perUser = snapshot.freeUserCount() <= 0
        ? BigDecimal.ZERO
        : snapshot.totalCny()
            .divide(BigDecimal.valueOf(snapshot.freeUserCount()), 6, RoundingMode.HALF_UP);
    return new QuotaConfigView(scale(snapshot.totalCny()), snapshot.freeUserCount(),
        scale(perUser));
  }

  private BigDecimal scale(BigDecimal value) {
    return value.setScale(4, RoundingMode.HALF_UP);
  }

  /**
   * 5 秒短缓存：额度按“总量 ÷ 免费用户数”实时算，但不必每次模型调用都查两次库。
   * 管理员保存总量时会主动清空缓存，因此配置变更仍能及时生效。
   */
  private CacheEntry snapshot() {
    long now = System.nanoTime();
    CacheEntry current = cache;
    if (current != null && current.expiresAtNanos() > now) {
      return current;
    }
    CacheEntry next = new CacheEntry(monthlyTotalCny(), freeUserCount(),
        now + Duration.ofSeconds(5).toNanos());
    cache = next;
    return next;
  }
}
