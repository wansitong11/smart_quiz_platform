package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import interview.guide.modules.aicost.PriceSource;
import interview.guide.modules.aicost.model.AiModelPriceEntity;
import interview.guide.modules.aicost.repository.AiModelPriceRepository;
import interview.guide.modules.aicost.service.AiCostCalculator.CostResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AiCostCalculatorTest {

  @Mock
  private AiModelPriceRepository priceRepository;

  @Test
  @DisplayName("按每百万 Token 单价计算输入 + 输出费用")
  void estimateFromPerMillionPrices() {
    AiModelPriceEntity price = AiModelPriceEntity.builder()
        .id(1L)
        .provider("dashscope")
        .model("qwen-plus")
        .modality("TEXT")
        .inputPricePer1m(new BigDecimal("0.40"))
        .outputPricePer1m(new BigDecimal("1.20"))
        .source(PriceSource.AUTO)
        .effectiveFrom(Instant.now().minusSeconds(60))
        .build();
    when(priceRepository.findCurrent(eq("dashscope"), eq("qwen-plus"), eq("TEXT"),
        any(Instant.class), any(Pageable.class))).thenReturn(List.of(price));

    AiCostCalculator calculator = new AiCostCalculator(priceRepository);
    CostResult result = calculator.estimate("dashscope", "qwen-plus", "TEXT", 1_000_000, 1_000_000, 0);

    assertThat(result.estimated()).isFalse();
    assertThat(result.amount()).isEqualByComparingTo("1.60");
  }

  @Test
  @DisplayName("查不到生效价格时标记 estimated 且金额为空")
  void markEstimatedWhenPriceMissing() {
    when(priceRepository.findCurrent(any(), any(), any(), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());

    AiCostCalculator calculator = new AiCostCalculator(priceRepository);
    CostResult result = calculator.estimate("unknown", "model", "TEXT", 1000, 100, 0);

    assertThat(result.estimated()).isTrue();
    assertThat(result.amount()).isNull();
  }

  @Test
  @DisplayName("当前价格输入输出缓存全为 0 时判定为免费")
  void detectsZeroPriceModelAsFree() {
    AiModelPriceEntity price = AiModelPriceEntity.builder()
        .provider("glm-free")
        .model("glm-4.7-flash")
        .modality("TEXT")
        .inputPricePer1m(BigDecimal.ZERO)
        .outputPricePer1m(BigDecimal.ZERO)
        .source(PriceSource.AUTO)
        .effectiveFrom(Instant.now().minusSeconds(60))
        .build();
    when(priceRepository.findCurrent(eq("glm-free"), eq("glm-4.7-flash"), eq("TEXT"),
        any(Instant.class), any(Pageable.class))).thenReturn(List.of(price));

    AiCostCalculator calculator = new AiCostCalculator(priceRepository);

    assertThat(calculator.freeStatus("glm-free", "glm-4.7-flash", "text"))
        .contains(true);
  }

  @Test
  @DisplayName("有非零价格时不判定为免费")
  void nonZeroPriceIsNotFree() {
    AiModelPriceEntity price = AiModelPriceEntity.builder()
        .provider("siliconflow-cheap")
        .model("Qwen/Qwen3-14B")
        .modality("TEXT")
        .inputPricePer1m(new BigDecimal("0.50"))
        .outputPricePer1m(new BigDecimal("2.00"))
        .source(PriceSource.AUTO)
        .effectiveFrom(Instant.now().minusSeconds(60))
        .build();
    when(priceRepository.findCurrent(eq("siliconflow-cheap"), eq("Qwen/Qwen3-14B"), eq("TEXT"),
        any(Instant.class), any(Pageable.class))).thenReturn(List.of(price));

    AiCostCalculator calculator = new AiCostCalculator(priceRepository);

    assertThat(calculator.freeStatus("siliconflow-cheap", "Qwen/Qwen3-14B", "TEXT"))
        .contains(false);
  }
}
