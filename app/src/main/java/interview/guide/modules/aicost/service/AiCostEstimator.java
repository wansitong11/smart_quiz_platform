package interview.guide.modules.aicost.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.modules.aicost.AiCostProperties;
import interview.guide.modules.aicost.AiCostProperties.Estimator;
import interview.guide.modules.aicost.service.AiCostCalculator.CostResult;
import java.math.BigDecimal;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AiCostEstimator {

  private final AiCostProperties properties;
  private final AiCostCalculator costCalculator;
  private final LlmProviderRegistry providerRegistry;

  public AiCostEstimator(
      AiCostProperties properties,
      AiCostCalculator costCalculator,
      @Lazy LlmProviderRegistry providerRegistry
  ) {
    this.properties = properties;
    this.costCalculator = costCalculator;
    this.providerRegistry = providerRegistry;
  }

  public BigDecimal estimate(AiFeature feature, AiCostEstimateInput input) {
    Estimator cfg = properties.getEstimator();
    long inputTokens = estimateInputTokens(feature, input, cfg);
    long outputTokens = estimateOutputTokens(feature, input, cfg);
    Resolution resolution = resolve(feature);
    if (resolution != null) {
      CostResult cost = costCalculator.estimate(
          resolution.provider(), resolution.model(), resolution.modality(),
          inputTokens, outputTokens, 0);
      if (!cost.estimated() && cost.amount() != null) {
        return cost.amount();
      }
    }
    return cfg.getDefaultOperationCny();
  }

  private long estimateInputTokens(AiFeature feature, AiCostEstimateInput input, Estimator cfg) {
    return switch (feature) {
      case PAPER_IMPORT, PAPER_RECOGNIZE, PAPER_EXTRACTION ->
          Math.max(1, nvl(input.pageCount()) * cfg.getPaperTokensPerPage());
      case QUIZ_GENERATE, KB_QUESTION_GEN ->
          Math.max(1, nvl(input.questionCount()) * cfg.getQuizTokensPerQuestion());
      case KB_QA -> Math.max(1, input.contextTokens() == null || input.contextTokens() <= 0
          ? cfg.getQaContextTokens() : input.contextTokens());
      case KB_VECTORIZE -> Math.max(1, nvl(input.chunkCount()) * cfg.getVectorizeTokensPerChunk());
      default -> 2000;
    };
  }

  private long estimateOutputTokens(AiFeature feature, AiCostEstimateInput input, Estimator cfg) {
    if (feature == AiFeature.KB_QA) {
      return input.outputTokens() != null && input.outputTokens() > 0
          ? input.outputTokens() : cfg.getQaOutputTokens();
    }
    return switch (feature) {
      case PAPER_IMPORT, PAPER_RECOGNIZE, PAPER_EXTRACTION -> estimateInputTokens(feature, input, cfg) / 4;
      case QUIZ_GENERATE, KB_QUESTION_GEN -> nvl(input.questionCount()) * 200;
      default -> 500;
    };
  }

  private Resolution resolve(AiFeature feature) {
    return switch (feature) {
      case PAPER_IMPORT, PAPER_RECOGNIZE, PAPER_EXTRACTION -> new Resolution(
          providerRegistry.getCurrentUserVisionProviderId(),
          providerRegistry.getCurrentUserVisionModelName(),
          "VISION");
      case KB_VECTORIZE -> new Resolution(
          providerRegistry.getDefaultEmbeddingProviderId(),
          providerRegistry.getDefaultEmbeddingModelName(),
          "EMBEDDING");
      default -> new Resolution(
          providerRegistry.getCurrentUserChatProviderId(),
          providerRegistry.getCurrentUserChatModelName(),
          "TEXT");
    };
  }

  private long nvl(Long value) {
    return value == null || value <= 0 ? 1 : value;
  }

  private record Resolution(String provider, String model, String modality) {
  }
}
