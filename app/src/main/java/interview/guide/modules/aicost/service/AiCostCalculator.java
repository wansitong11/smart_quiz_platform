package interview.guide.modules.aicost.service;

import interview.guide.modules.aicost.model.AiModelPriceEntity;
import interview.guide.modules.aicost.repository.AiModelPriceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiCostCalculator {

  private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

  private final AiModelPriceRepository priceRepository;

  public record CostResult(
      BigDecimal amount,
      String currency,
      String priceVersion,
      boolean estimated
  ) {
  }

  public CostResult estimate(
      String provider,
      String model,
      String modality,
      long inputTokens,
      long outputTokens,
      long cachedTokens
  ) {
    if (provider == null || model == null) {
      return new CostResult(null, "CNY", null, true);
    }
    List<AiModelPriceEntity> rows = priceRepository.findCurrent(
        provider, model, modality, Instant.now(), PageRequest.of(0, 1));
    if (rows.isEmpty()) {
      return new CostResult(null, "CNY", null, true);
    }
    AiModelPriceEntity price = rows.get(0);
    BigDecimal amount = amountFor(price, inputTokens, outputTokens, cachedTokens);
    return new CostResult(amount, price.getCurrency(), "v" + price.getId(), false);
  }

  public BigDecimal amountFor(
      AiModelPriceEntity price,
      long inputTokens,
      long outputTokens,
      long cachedTokens
  ) {
    BigDecimal amount = BigDecimal.ZERO;
    amount = add(amount, price.getInputPricePer1m(), inputTokens);
    amount = add(amount, price.getOutputPricePer1m(), outputTokens);
    amount = add(amount, price.getCachedPricePer1m(), cachedTokens);
    return amount;
  }

  /**
   * 查询当前生效价格是否全部为 0。
   *
   * <p>返回空表示这个 Provider / 模型 / 模态还没有价格记录，调用方可以自行决定回退策略。</p>
   */
  public Optional<Boolean> freeStatus(String provider, String model, String modality) {
    if (provider == null || model == null || modality == null) {
      return Optional.empty();
    }
    List<AiModelPriceEntity> rows = priceRepository.findCurrent(
        provider,
        model,
        modality.trim().toUpperCase(Locale.ROOT),
        Instant.now(),
        PageRequest.of(0, 1));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    AiModelPriceEntity price = rows.get(0);
    return Optional.of(
        isZero(price.getInputPricePer1m())
            && isZero(price.getOutputPricePer1m())
            && isZero(price.getCachedPricePer1m()));
  }

  private BigDecimal add(BigDecimal total, BigDecimal pricePer1m, long tokens) {
    if (pricePer1m == null || tokens <= 0) {
      return total;
    }
    BigDecimal perToken = pricePer1m.divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
    return total.add(perToken.multiply(BigDecimal.valueOf(tokens)));
  }

  private boolean isZero(BigDecimal value) {
    return value == null || value.compareTo(BigDecimal.ZERO) == 0;
  }
}
