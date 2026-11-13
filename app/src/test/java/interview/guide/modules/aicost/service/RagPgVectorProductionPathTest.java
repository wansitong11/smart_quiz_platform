package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiRateLimitThrottle;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmEmbeddingConfig;
import interview.guide.modules.aicost.AiCostProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@EnabledIfEnvironmentVariable(named = "RAG_PG_INTEGRATION", matches = "true")
class RagPgVectorProductionPathTest {
  @Test
  @DisplayName("生产嵌入代理与计量包装写入真实向量时正文和检索输入分离")
  void proxyAndMeterPreserveStoredBody() throws Exception {
    var requests = new ArrayList<List<String>>();
    EmbeddingModel fake = new EmbeddingModel() {
      @Override
      public EmbeddingResponse call(EmbeddingRequest request) {
        requests.add(List.copyOf(request.getInstructions()));
        var vectors = new ArrayList<Embedding>();
        for (int index = 0; index < request.getInstructions().size(); index++) {
          vectors.add(new Embedding(new float[] {1, 0, 0}, index));
        }
        return new EmbeddingResponse(vectors);
      }

      @Override
      public float[] embed(Document document) {
        return embed(document.getText());
      }
    };
    var providerGate = mock(ProviderConcurrencyGate.class);
    var freeGate = mock(FreeModelConcurrencyGate.class);
    when(providerGate.acquire(anyString(), anyString())).thenReturn(ProviderConcurrencyGate.Permit.noop());
    when(freeGate.acquireIfFree(anyString(), anyString(), anyString()))
        .thenReturn(FreeModelConcurrencyGate.Permit.noop());
    var costProperties = new AiCostProperties();
    costProperties.setEnabled(false);
    var recorder = new AiCallRecorder(costProperties, mock(AiUsageGate.class), mock(AiCostCalculator.class),
        mock(AiLedgerWriter.class), mock(AiBudgetService.class), freeGate, providerGate,
        mock(AiRateLimitThrottle.class));
    var registry = mock(LlmProviderRegistry.class);
    when(registry.getDefaultEmbeddingModel()).thenReturn(recorder.wrapEmbedding(fake, "test", "fake"));
    EmbeddingModel proxy = new LlmEmbeddingConfig().embeddingModel(registry);
    try (var dataSource = new SingleConnectionDataSource(
        System.getenv("RAG_PG_URL"), System.getenv("RAG_PG_USER"), System.getenv("RAG_PG_PASSWORD"), true)) {
      var jdbc = new JdbcTemplate(dataSource);
      jdbc.execute("CREATE TEMP TABLE vector_store (id uuid PRIMARY KEY, content text, metadata json, embedding vector(3))");
      var store = PgVectorStore.builder(jdbc, proxy).schemaName("pg_temp")
          .vectorTableName("vector_store").dimensions(3).initializeSchema(false).build();
      store.add(List.of(new Document("00000000-0000-0000-0000-000000000011", "申请后补充凭证。",
          Map.of("chunk_strategy", "section-neighbor-v2", "document_title", "退款规范",
              "section_path", "退款 > 特殊商品", "kb_id", "1"))));
      assertThat(requests).hasSize(1);
      assertThat(requests.getFirst()).containsExactly("[资料：退款规范]\n[章节：退款 > 特殊商品]\n申请后补充凭证。");
      assertThat(jdbc.queryForObject("SELECT content FROM vector_store", String.class)).isEqualTo("申请后补充凭证。");
      assertThat(store.similaritySearch(SearchRequest.builder().query("凭证").topK(1).build()))
          .singleElement().satisfies(document -> {
            assertThat(document.getText()).isEqualTo("申请后补充凭证。");
            assertThat(document.getMetadata()).containsEntry("section_path", "退款 > 特殊商品");
          });
      verify(providerGate, org.mockito.Mockito.times(2)).acquire("test", "EMBEDDING");
    }
  }
}
