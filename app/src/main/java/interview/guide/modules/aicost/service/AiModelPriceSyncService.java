package interview.guide.modules.aicost.service;

import interview.guide.modules.aicost.PriceSource;
import interview.guide.modules.aicost.dto.AiUsageDtos.PriceSyncResult;
import interview.guide.modules.aicost.model.AiModelPriceEntity;
import interview.guide.modules.aicost.repository.AiModelPriceRepository;
import interview.guide.modules.aicost.service.AiModelPriceSource.PriceSeed;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelPriceSyncService {

  private final AiModelPriceRepository priceRepository;
  private final AiModelPriceSource source;

  @Transactional
  public PriceSyncResult syncNow() {
    Instant now = Instant.now();
    int applied = 0;
    AiModelPriceSource.PriceSeeds seeds = source.loadWithSource();
    for (PriceSeed seed : seeds.seeds()) {
      if (!priceRepository.findActiveManual(
          seed.provider(), seed.model(), seed.modality(), now).isEmpty()) {
        continue;
      }
      List<AiModelPriceEntity> activeAuto = priceRepository.findActiveAuto(
          seed.provider(), seed.model(), seed.modality(), now);
      if (activeAuto.stream().anyMatch(auto -> samePrice(auto, seed))) {
        continue;
      }
      activeAuto.forEach(auto -> auto.setEffectiveTo(now));
      priceRepository.saveAll(activeAuto);
      priceRepository.save(AiModelPriceEntity.builder()
          .provider(seed.provider())
          .model(seed.model())
          .modality(seed.modality())
          .inputPricePer1m(seed.inputPricePer1m())
          .outputPricePer1m(seed.outputPricePer1m())
          .cachedPricePer1m(seed.cachedPricePer1m())
          .unit("TOKEN")
          .currency("CNY")
          .source(PriceSource.AUTO)
          .effectiveFrom(now)
          .build());
      applied++;
    }
    if (applied > 0) {
      log.info("[AiModelPriceSyncService] 已同步 {} 条模型价格", applied);
    }
    return new PriceSyncResult(applied, seeds.source());
  }

  private boolean samePrice(AiModelPriceEntity entity, PriceSeed seed) {
    return eq(entity.getInputPricePer1m(), seed.inputPricePer1m())
        && eq(entity.getOutputPricePer1m(), seed.outputPricePer1m())
        && eq(entity.getCachedPricePer1m(), seed.cachedPricePer1m());
  }

  private boolean eq(BigDecimal a, BigDecimal b) {
    if (a == null && b == null) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    return a.compareTo(b) == 0;
  }
}
