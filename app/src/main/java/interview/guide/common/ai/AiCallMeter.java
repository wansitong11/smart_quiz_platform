package interview.guide.common.ai;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 模型调用计量与闸门插口。实现方放在业务模块，common 只依赖该接口，
 * 避免 common -> modules 的反向依赖。
 */
public interface AiCallMeter {

  Advisor chatAdvisor(String providerId, String model, String modality);

  EmbeddingModel wrapEmbedding(EmbeddingModel delegate, String providerId, String model);
}
