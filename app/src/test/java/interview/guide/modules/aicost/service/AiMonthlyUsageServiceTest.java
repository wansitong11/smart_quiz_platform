package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import interview.guide.modules.aicost.PriceSource;
import interview.guide.modules.aicost.model.AiCallEventEntity;
import interview.guide.modules.aicost.model.AiModelPriceEntity;
import interview.guide.modules.aicost.repository.AiCallEventRepository;
import interview.guide.modules.aicost.repository.AiModelPriceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("用户当月账本成本")
class AiMonthlyUsageServiceTest {

  private static final Long USER_ID = 3L;

  @Mock
  private AiCallEventRepository eventRepository;

  @Mock
  private AiModelPriceRepository priceRepository;

  @Mock
  private AiCostCalculator costCalculator;

  @Test
  @DisplayName("CNY 记录直接取账本金额，历史 USD 记录按 CNY 单价重算")
  void shouldUseCnyCostDirectlyAndRepriceUsdRows() {
    Instant now = Instant.now();
    AiModelPriceEntity price = AiModelPriceEntity.builder()
        .provider("dashscope")
        .model("qwen3.8-flash")
        .modality("TEXT")
        .inputPricePer1m(new BigDecimal("0.8"))
        .outputPricePer1m(new BigDecimal("2.7"))
        .currency("CNY")
        .source(PriceSource.MANUAL)
        .effectiveFrom(now.minusSeconds(60))
        .build();
    AiCallEventEntity cny = event("CNY", "0.20", now);
    AiCallEventEntity usd = event("USD", "9.99", now);

    when(eventRepository
        .findByUserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            eq(USER_ID), any(Instant.class), any(Instant.class)))
        .thenReturn(List.of(cny, usd));
    when(priceRepository.findActive(any(Instant.class))).thenReturn(List.of(price));
    when(costCalculator.amountFor(price, 1000L, 1000L, 0L))
        .thenReturn(new BigDecimal("0.30"));

    AiMonthlyUsageService service =
        new AiMonthlyUsageService(eventRepository, priceRepository, costCalculator);

    assertThat(service.currentMonthCny(USER_ID)).isEqualByComparingTo("0.50");
  }

  @Test
  @DisplayName("历史非 CNY 记录找不到当前 CNY 价格时按 0 计入")
  void shouldIgnoreUsdRowsWithoutCnyPrice() {
    Instant now = Instant.now();
    when(eventRepository
        .findByUserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            eq(USER_ID), any(Instant.class), any(Instant.class)))
        .thenReturn(List.of(event("USD", "0.50", now)));
    when(priceRepository.findActive(any(Instant.class))).thenReturn(List.of());

    AiMonthlyUsageService service =
        new AiMonthlyUsageService(eventRepository, priceRepository, costCalculator);

    assertThat(service.currentMonthCny(USER_ID)).isZero();
  }

  private AiCallEventEntity event(String currency, String cost, Instant occurredAt) {
    return AiCallEventEntity.builder()
        .provider("dashscope")
        .model("qwen3.8-flash")
        .modality("TEXT")
        .estimatedCost(new BigDecimal(cost))
        .currency(currency)
        .inputTokens(1000L)
        .outputTokens(1000L)
        .cachedTokens(0L)
        .occurredAt(occurredAt)
        .build();
  }
}
