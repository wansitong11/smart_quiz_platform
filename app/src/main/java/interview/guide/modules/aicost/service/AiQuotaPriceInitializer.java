package interview.guide.modules.aicost.service;

import interview.guide.modules.aicost.PriceSource;
import interview.guide.modules.aicost.model.AiModelPriceEntity;
import interview.guide.modules.aicost.repository.AiModelPriceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动时确保两档模型的人民币价格存在。
 *
 * <p>只补齐缺失的 CNY 价格，不覆盖管理员手工改过的价格；旧 USD 行会被关闭，
 * 避免同一 Provider/模型同时命中两条有效价格。</p>
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class AiQuotaPriceInitializer implements ApplicationRunner {

  private final AiModelPriceRepository priceRepository;

  private record Seed(
      String provider,
      String model,
      String modality,
      BigDecimal input,
      BigDecimal output
  ) {
  }

  private static final List<Seed> SEEDS = List.of(
      new Seed("dashscope", "qwen3.8-flash", "TEXT",
          new BigDecimal("0.8"), new BigDecimal("2.7")),
      new Seed("dashscope-vl", "qwen3.8-flash", "VISION",
          new BigDecimal("0.8"), new BigDecimal("2.7")),
      new Seed("dashscope-3.7", "qwen3.7-flash", "TEXT",
          new BigDecimal("0.2"), new BigDecimal("0.8")),
      new Seed("dashscope-3.7-vl", "qwen3.7-flash", "VISION",
          new BigDecimal("0.2"), new BigDecimal("0.8"))
  );

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    Instant now = Instant.now();
    List<AiModelPriceEntity> active = priceRepository.findActive(now);
    for (Seed seed : SEEDS) {
      boolean already = active.stream().anyMatch(price -> matches(price, seed)
          && "CNY".equalsIgnoreCase(price.getCurrency())
          && same(price.getInputPricePer1m(), seed.input())
          && same(price.getOutputPricePer1m(), seed.output()));
      if (already) {
        continue;
      }
      active.stream()
          .filter(price -> matches(price, seed))
          .forEach(price -> price.setEffectiveTo(now));
      priceRepository.save(AiModelPriceEntity.builder()
          .provider(seed.provider())
          .model(seed.model())
          .modality(seed.modality())
          .inputPricePer1m(seed.input())
          .outputPricePer1m(seed.output())
          .cachedPricePer1m(null)
          .unit("TOKEN")
          .currency("CNY")
          .source(PriceSource.MANUAL)
          .effectiveFrom(now)
          .build());
      log.info("[AiQuotaPriceInitializer] 写入 CNY 价格: {}/{} {}",
          seed.provider(), seed.model(), seed.modality());
    }
  }

  private boolean matches(AiModelPriceEntity price, Seed seed) {
    return seed.provider().equals(price.getProvider())
        && seed.model().equals(price.getModel())
        && seed.modality().equals(price.getModality());
  }

  private boolean same(BigDecimal left, BigDecimal right) {
    return left != null && right != null && left.compareTo(right) == 0;
  }
}
