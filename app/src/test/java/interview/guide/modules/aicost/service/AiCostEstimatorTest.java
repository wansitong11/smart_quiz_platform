package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.aicost.AiCostProperties;
import interview.guide.modules.aicost.service.AiCostCalculator.CostResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiCostEstimatorTest {

  @Mock
  private AiCostCalculator costCalculator;

  @Mock
  private LlmProviderRegistry providerRegistry;

  @Test
  @DisplayName("按页数估算识图成本")
  void estimatePaperByPages() {
    when(providerRegistry.getCurrentUserVisionProviderId()).thenReturn("dashscope-vl");
    when(providerRegistry.getCurrentUserVisionModelName()).thenReturn("qwen3.8-flash");
    when(costCalculator.estimate(eq("dashscope-vl"), eq("qwen3.8-flash"), eq("VISION"),
        anyLong(), anyLong(), anyLong()))
        .thenReturn(new CostResult(new BigDecimal("0.50"), "CNY", "v1", false));

    AiCostEstimator estimator = new AiCostEstimator(new AiCostProperties(), costCalculator,
        providerRegistry);
    BigDecimal amount = estimator.estimate(AiFeature.PAPER_IMPORT,
        new AiCostEstimateInput(2L, null, null, null, null));

    assertThat(amount).isEqualByComparingTo("0.50");
  }

  @Test
  @DisplayName("无生效价格时回退默认单次成本")
  void fallbackToDefaultWhenPriceMissing() {
    when(providerRegistry.getCurrentUserChatProviderId()).thenReturn("dashscope");
    when(providerRegistry.getCurrentUserChatModelName()).thenReturn("qwen-plus");
    when(costCalculator.estimate(eq("dashscope"), eq("qwen-plus"), eq("TEXT"),
        anyLong(), anyLong(), anyLong()))
        .thenReturn(new CostResult(null, "CNY", null, true));

    AiCostEstimator estimator = new AiCostEstimator(new AiCostProperties(), costCalculator,
        providerRegistry);
    BigDecimal amount = estimator.estimate(AiFeature.KB_QA,
        new AiCostEstimateInput(null, null, null, null, null));

    assertThat(amount).isEqualByComparingTo("0.01");
  }
}
