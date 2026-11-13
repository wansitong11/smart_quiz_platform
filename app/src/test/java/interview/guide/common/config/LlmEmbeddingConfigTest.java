package interview.guide.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import interview.guide.common.ai.LlmProviderRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class LlmEmbeddingConfigTest {
  @Test
  @DisplayName("新策略嵌入输入包含资料和章节但不修改原文")
  void formatsEmbeddingInputWithoutChangingBody() {
    var model = new LlmEmbeddingConfig().embeddingModel(mock(LlmProviderRegistry.class));
    var document = new Document("原文", Map.of("chunk_strategy", "section-neighbor-v2",
        "document_title", "退款规范", "section_path", "退款 > 特殊商品"));
    assertThat(model.getEmbeddingContent(document))
        .isEqualTo("[资料：退款规范]\n[章节：退款 > 特殊商品]\n原文");
    assertThat(document.getText()).isEqualTo("原文");
    assertThat(model.getEmbeddingContent(new Document("旧正文"))).isEqualTo("旧正文");
  }
}
