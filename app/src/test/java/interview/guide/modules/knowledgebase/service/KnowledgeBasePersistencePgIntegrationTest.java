package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@EnabledIfEnvironmentVariable(named = "RAG_PG_INTEGRATION", matches = "true")
class KnowledgeBasePersistencePgIntegrationTest {
  @Test
  @DisplayName("生产事务代理提交新向量，失效代次及保存失败不破坏旧向量")
  void transactionProxyCommitsAndRollsBackVectorPromotion() throws Exception {
    try (var dataSource = new SingleConnectionDataSource(
        System.getenv("RAG_PG_URL"), System.getenv("RAG_PG_USER"), System.getenv("RAG_PG_PASSWORD"), true)) {
      var jdbc = new JdbcTemplate(dataSource);
      jdbc.execute("CREATE TEMP TABLE vector_store (id uuid PRIMARY KEY, content text, metadata json)");
      var knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
      var knowledgeBase = new KnowledgeBaseEntity();
      knowledgeBase.setVectorStatus(VectorStatus.PROCESSING);
      knowledgeBase.setVectorAttemptId("current");
      when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(knowledgeBase));
      var target = new KnowledgeBasePersistenceService(knowledgeBaseRepository, new VectorRepository(jdbc),
          mock(KbCategoryService.class), mock(OwnerScope.class), mock(KnowledgeBaseAccessService.class));
      var transaction = new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
          new AnnotationTransactionAttributeSource());
      var proxyFactory = new ProxyFactory(target);
      proxyFactory.addAdvice(transaction);
      var service = (KnowledgeBasePersistenceService) proxyFactory.getProxy();

      jdbc.update("INSERT INTO vector_store VALUES (?::uuid, ?, ?::json)",
          "00000000-0000-0000-0000-000000000001", "旧正文", "{\"kb_id\":\"1\"}");
      jdbc.update("INSERT INTO vector_store VALUES (?::uuid, ?, ?::json)",
          "00000000-0000-0000-0000-000000000002", "新正文",
          "{\"kb_id\":\"pending:1:job\",\"kb_vector_job_id\":\"job\",\"kb_target_id\":\"1\"}");
      assertThatThrownBy(() -> service.activateVectorJobAndUpdateSnapshot(1L, "old", "job", 1, "{}"))
          .isInstanceOf(BusinessException.class);
      assertThat(jdbc.queryForList("SELECT content FROM vector_store ORDER BY content", String.class))
          .containsExactlyInAnyOrder("旧正文", "新正文");

      when(knowledgeBaseRepository.save(any())).thenAnswer(invocation -> {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        throw new IllegalStateException("模拟快照保存失败");
      });
      assertThatThrownBy(() -> service.activateVectorJobAndUpdateSnapshot(1L, "current", "job", 1, "{}"))
          .isInstanceOf(IllegalStateException.class);
      assertThat(jdbc.queryForList("SELECT content FROM vector_store ORDER BY content", String.class))
          .containsExactlyInAnyOrder("旧正文", "新正文");
      assertThat(jdbc.queryForObject("SELECT metadata->>'kb_vector_job_id' FROM vector_store WHERE content='新正文'",
          String.class)).isEqualTo("job");

      doAnswer(invocation -> invocation.getArgument(0)).when(knowledgeBaseRepository).save(any());
      service.activateVectorJobAndUpdateSnapshot(1L, "current", "job", 1, "{}");
      assertThat(jdbc.queryForList("SELECT content FROM vector_store", String.class)).isEqualTo(List.of("新正文"));
      assertThat(jdbc.queryForObject("SELECT metadata->>'kb_id' FROM vector_store", String.class)).isEqualTo("1");
      assertThat(knowledgeBase.getChunkCount()).isEqualTo(1);
    }
  }
}
