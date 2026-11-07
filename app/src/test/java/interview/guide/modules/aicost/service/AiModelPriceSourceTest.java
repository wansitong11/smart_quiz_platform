package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.aicost.AiCostProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiModelPriceSourceTest {

  @Test
  @DisplayName("内置价格覆盖数据库当前默认文本模型")
  void bundledPricesContainCurrentTextModel() {
    AiModelPriceSource source = new AiModelPriceSource(new AiCostProperties(), new ObjectMapper());

    assertThat(source.load())
        .anySatisfy(price -> {
          assertThat(price.provider()).isEqualTo("dashscope");
          assertThat(price.model()).isEqualTo("qwen3.8-flash");
          assertThat(price.modality()).isEqualTo("TEXT");
          assertThat(price.inputPricePer1m()).isEqualByComparingTo(new BigDecimal("0.80"));
          assertThat(price.outputPricePer1m()).isEqualByComparingTo(new BigDecimal("2.70"));
        });
  }
}
