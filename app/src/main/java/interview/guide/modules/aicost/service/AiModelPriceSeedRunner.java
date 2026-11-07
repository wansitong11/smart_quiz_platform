package interview.guide.modules.aicost.service;

import interview.guide.modules.aicost.AiCostProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时灌入内置价目表，保证首次部署后价格表立即可用。
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class AiModelPriceSeedRunner implements ApplicationRunner {

  private final AiCostProperties properties;
  private final AiModelPriceSyncService syncService;

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.getPrices().isSeedOnStartup()) {
      return;
    }
    try {
      var result = syncService.syncNow();
      log.info("[AiModelPriceSeedRunner] 启动灌价完成: applied={}, source={}",
          result.applied(), result.source());
    } catch (Exception e) {
      log.warn("[AiModelPriceSeedRunner] 启动灌价失败，忽略并继续启动", e);
    }
  }
}
