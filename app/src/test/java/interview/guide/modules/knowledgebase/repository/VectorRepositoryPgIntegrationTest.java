package interview.guide.modules.knowledgebase.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@EnabledIfEnvironmentVariable(named = "RAG_PG_INTEGRATION", matches = "true")
class VectorRepositoryPgIntegrationTest {
  @Test
  @DisplayName("临时向量提升、同代邻居查询与事务回滚在真实 PostgreSQL 中隔离")
  void promotionNeighborsAndRollback() throws Exception {
    try (var dataSource = new SingleConnectionDataSource(
        System.getenv("RAG_PG_URL"), System.getenv("RAG_PG_USER"), System.getenv("RAG_PG_PASSWORD"), true)) {
      var jdbc = new JdbcTemplate(dataSource);
      jdbc.execute("CREATE TEMP TABLE vector_store (id uuid PRIMARY KEY, content text, metadata json)");
      String parent = "d680d0b3-1200-4ec7-89d5-26a2ed58de11";
      jdbc.update("INSERT INTO vector_store VALUES (?::uuid, ?, ?::json)",
          "00000000-0000-0000-0000-000000000001", "条件",
          "{\"kb_id\":\"pending:1:job\",\"kb_target_id\":\"1\",\"kb_vector_job_id\":\"job\","
              + "\"chunk_strategy\":\"section-neighbor-v2\",\"parent_document_id\":\"" + parent
              + "\",\"chunk_index\":0,\"total_chunks\":2,\"section_path\":\"规则\"}");
      var repository = new VectorRepository(jdbc);
      var key = new VectorRepository.NeighborKey(1L, parent, 0);
      assertThat(repository.findNeighbors(List.of(key))).isEmpty();
      var connection = dataSource.getConnection();
      connection.setAutoCommit(false);
      assertThat(repository.promoteVectorJob(1L, "job")).isEqualTo(1);
      assertThat(repository.findNeighbors(List.of(key))).singleElement()
          .satisfies(chunk -> assertThat(chunk.content()).isEqualTo("条件"));
      assertThat(repository.findNeighbors(List.of(new VectorRepository.NeighborKey(2L, parent, 0))))
          .isEmpty();
      assertThat(repository.findNeighbors(List.of(new VectorRepository.NeighborKey(1L,
          "00000000-0000-0000-0000-000000000002", 0)))).isEmpty();
      connection.rollback();
      assertThat(repository.findNeighbors(List.of(key))).isEmpty();
      assertThat(jdbc.queryForObject("SELECT metadata->>'kb_vector_job_id' FROM vector_store", String.class))
          .isEqualTo("job");
    }
  }
}
