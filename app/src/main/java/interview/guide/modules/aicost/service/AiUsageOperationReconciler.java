package interview.guide.modules.aicost.service;

import interview.guide.modules.aicost.AiCostProperties;
import interview.guide.modules.aicost.AiOperationStatus;
import interview.guide.modules.aicost.model.AiUsageOperationEntity;
import interview.guide.modules.aicost.repository.AiUsageOperationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预占对账：把挂起超过阈值、仍未结算的 RESERVED 操作按实际调用结算或释放。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiUsageOperationReconciler {

  private final AiCostProperties properties;
  private final AiUsageOperationRepository operationRepository;
  private final AiBudgetService budgetService;

  @Scheduled(cron = "${app.ai.cost.budget.reconciliation-cron:0 */15 * * * ?}",
      zone = "${app.ai.cost.prices.sync-zone:Asia/Shanghai}")
  public void reconcile() {
    Instant cutoff = Instant.now()
        .minus(Duration.ofHours(properties.getBudget().getStaleReservedHours()));
    List<AiUsageOperationEntity> stale = operationRepository
        .findByStatusAndStartedAtBefore(AiOperationStatus.RESERVED.name(), cutoff);
    for (AiUsageOperationEntity op : stale) {
      try {
        budgetService.finalize(op.getOperationId());
      } catch (Exception e) {
        log.warn("[AiUsageOperationReconciler] 预占对账失败: operationId={}",
            op.getOperationId(), e);
      }
    }
    if (!stale.isEmpty()) {
      log.info("[AiUsageOperationReconciler] 已对账 {} 条挂起预占", stale.size());
    }
  }
}
