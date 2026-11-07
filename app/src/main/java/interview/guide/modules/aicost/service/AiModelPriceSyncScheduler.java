package interview.guide.modules.aicost.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelPriceSyncScheduler {

  private final AiModelPriceSyncService syncService;

  @Scheduled(cron = "${app.ai.cost.prices.sync-cron:0 20 4 * * ?}",
      zone = "${app.ai.cost.prices.sync-zone:Asia/Shanghai}")
  public void syncDaily() {
    try {
      syncService.syncNow();
    } catch (Exception e) {
      log.warn("[AiModelPriceSyncScheduler] 每日价格同步失败", e);
    }
  }
}
