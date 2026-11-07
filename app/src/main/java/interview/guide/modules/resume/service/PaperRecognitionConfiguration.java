package interview.guide.modules.resume.service;

import interview.guide.common.ai.AiFeature;
import interview.guide.common.ai.VisionRequestGuardProperties;
import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.BoundedExecutorFactory;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.aicost.AiCostProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 试卷识别专用页级执行器；页并发永远不能超过单 provider 的准入上限。
 */
@Configuration
public class PaperRecognitionConfiguration {

  @Bean(name = "paperRecognitionExecutor", destroyMethod = "shutdown")
  public BoundedExecutor paperRecognitionExecutor(
      PaperRecognitionExecutorProperties properties,
      VisionRequestGuardProperties visionProperties,
      LlmProviderProperties llmProperties,
      AiCostProperties aiCostProperties,
      BoundedExecutorFactory factory
  ) {
    if (properties.getPoolSize() > visionProperties.getMaxInFlight()) {
      throw new IllegalStateException(
          "app.paper.recognition.pool-size 不能大于 app.ai.vision-guard.max-in-flight");
    }
    if (properties.getPoolSize() > paperImportConcurrency(aiCostProperties)) {
      throw new IllegalStateException(
          "app.paper.recognition.pool-size 不能大于 PAPER_IMPORT 预算并发；"
              + "请同步调整 APP_AI_BUDGET_PAPER_IMPORT_CONCURRENCY");
    }
    if (properties.isTwoStageRecognitionEnabled()
        && properties.getMaxVisionCallsPerPage() < 2) {
      throw new IllegalStateException(
          "两阶段识别至少需要 2 次单页视觉调用预算");
    }
    if (properties.getPageStaleThreshold()
        .compareTo(llmProperties.getVisionRequestTimeout()) <= 0) {
      throw new IllegalStateException(
          "paper.recognition.page-stale-threshold 必须大于 vision-request-timeout");
    }
    if (properties.getPageStaleThreshold().compareTo(
        llmProperties.getVisionRequestTimeout()
            .multipliedBy(properties.getMaxVisionCallsPerPage())
            .plus(properties.getHeartbeatInterval())) <= 0) {
      throw new IllegalStateException(
          "paper.recognition.page-stale-threshold 必须大于"
              + " vision-request-timeout × max-vision-calls-per-page + heartbeat-interval");
    }
    if (properties.getHeartbeatInterval()
        .compareTo(properties.getPageStaleThreshold()) >= 0) {
      throw new IllegalStateException(
          "paper.recognition.heartbeat-interval 必须小于 page-stale-threshold");
    }
    return factory.create("paper-recognition", properties);
  }

  private int paperImportConcurrency(AiCostProperties properties) {
    AiCostProperties.FeatureBudget feature =
        properties.getBudget().getFeatures().get(AiFeature.PAPER_IMPORT.name());
    Integer featureConcurrency = feature == null ? null : feature.getConcurrency();
    return featureConcurrency == null
        ? properties.getBudget().getDefaultRules().getConcurrency()
        : featureConcurrency;
  }
}
