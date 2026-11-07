package interview.guide.modules.resume.service;

import interview.guide.common.ai.VisionRequestGuardProperties;
import interview.guide.common.async.BoundedExecutorFactory;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.aicost.AiCostProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("试卷页识别配置校验")
class PaperRecognitionConfigurationTest {

  private final PaperRecognitionConfiguration configuration =
      new PaperRecognitionConfiguration();

  @Test
  @DisplayName("页池超过 PAPER_IMPORT 预算并发时启动失败")
  void rejectsPagePoolAbovePaperImportBudget() {
    PaperRecognitionExecutorProperties properties =
        new PaperRecognitionExecutorProperties();
    AiCostProperties aiCostProperties = new AiCostProperties();
    AiCostProperties.FeatureBudget featureBudget = new AiCostProperties.FeatureBudget();
    featureBudget.setConcurrency(2);
    aiCostProperties.getBudget().getFeatures().put("PAPER_IMPORT", featureBudget);

    assertThatThrownBy(() -> configuration.paperRecognitionExecutor(
        properties,
        new VisionRequestGuardProperties(),
        new LlmProviderProperties(),
        aiCostProperties,
        mock(BoundedExecutorFactory.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PAPER_IMPORT");
  }
}
