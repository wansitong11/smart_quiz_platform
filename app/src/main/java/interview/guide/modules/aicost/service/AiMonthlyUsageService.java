package interview.guide.modules.aicost.service;

import interview.guide.modules.aicost.PriceSource;
import interview.guide.modules.aicost.model.AiCallEventEntity;
import interview.guide.modules.aicost.model.AiModelPriceEntity;
import interview.guide.modules.aicost.repository.AiCallEventRepository;
import interview.guide.modules.aicost.repository.AiModelPriceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从调用明细账本汇总用户当前自然月成本。
 *
 * <p>账本当前同时存在历史 USD 行和新的 CNY 行。为了避免把旧 USD 金额按汇率再次放大，
 * CNY 行直接采用账本金额；非 CNY 行按 token 数用当前 CNY 价格表重算。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMonthlyUsageService {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private final AiCallEventRepository eventRepository;
  private final AiModelPriceRepository priceRepository;
  private final AiCostCalculator costCalculator;

  @Transactional(readOnly = true)
  public BigDecimal currentMonthCny(Long userId) {
    if (userId == null) {
      return BigDecimal.ZERO;
    }
    YearMonth month = YearMonth.now(ZONE);
    Instant from = month.atDay(1).atStartOfDay(ZONE).toInstant();
    Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
    List<AiCallEventEntity> events = eventRepository
        .findByUserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(userId, from, to);
    if (events.isEmpty()) {
      return BigDecimal.ZERO;
    }

    Instant now = Instant.now();
    Map<String, AiModelPriceEntity> cnyPrices = currentCnyPrices(now);
    BigDecimal total = BigDecimal.ZERO;
    for (AiCallEventEntity event : events) {
      BigDecimal cost = cnyCost(event, cnyPrices);
      if (cost != null && cost.signum() > 0) {
        total = total.add(cost);
      }
    }
    return total.setScale(6, RoundingMode.HALF_UP);
  }

  private BigDecimal cnyCost(
      AiCallEventEntity event,
      Map<String, AiModelPriceEntity> cnyPrices
  ) {
    if (event.getEstimatedCost() != null && "CNY".equalsIgnoreCase(event.getCurrency())) {
      return event.getEstimatedCost();
    }
    AiModelPriceEntity price = cnyPrices.get(priceKey(
        event.getProvider(), event.getModel(), event.getModality()));
    if (price == null) {
      log.debug("[AiMonthlyUsageService] 历史调用缺少当前 CNY 价格，按 0 计入: provider={}, "
              + "model={}, modality={}",
          event.getProvider(), event.getModel(), event.getModality());
      return BigDecimal.ZERO;
    }
    return costCalculator.amountFor(
        price,
        longValue(event.getInputTokens()),
        longValue(event.getOutputTokens()),
        longValue(event.getCachedTokens()));
  }

  private Map<String, AiModelPriceEntity> currentCnyPrices(Instant now) {
    Map<String, AiModelPriceEntity> prices = new HashMap<>();
    for (AiModelPriceEntity price : priceRepository.findActive(now)) {
      if (price.getEffectiveFrom() == null || price.getEffectiveFrom().isAfter(now)) {
        continue;
      }
      if (!"CNY".equalsIgnoreCase(price.getCurrency())) {
        continue;
      }
      String key = priceKey(price.getProvider(), price.getModel(), price.getModality());
      AiModelPriceEntity current = prices.get(key);
      if (current == null || preferred(price, current)) {
        prices.put(key, price);
      }
    }
    return prices;
  }

  private boolean preferred(AiModelPriceEntity candidate, AiModelPriceEntity current) {
    boolean candidateManual = candidate.getSource() == PriceSource.MANUAL;
    boolean currentManual = current.getSource() == PriceSource.MANUAL;
    if (candidateManual != currentManual) {
      return candidateManual;
    }
    return candidate.getEffectiveFrom().isAfter(current.getEffectiveFrom());
  }

  private String priceKey(String provider, String model, String modality) {
    return text(provider) + '\u0000' + text(model) + '\u0000' + text(modality);
  }

  private String text(String value) {
    return value == null ? "" : value.toUpperCase(Locale.ROOT);
  }

  private long longValue(Long value) {
    return value == null ? 0L : value;
  }
}
