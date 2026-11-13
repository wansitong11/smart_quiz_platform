package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.slf4j.LoggerFactory;

class RagContextAssemblerTest {
  @Test
  @DisplayName("仅显式开启诊断时记录直接命中、相邻补入和完整上下文")
  void logsContextOnlyWhenDiagnosticsEnabled() {
    var repository = mock(VectorRepository.class);
    var names = mock(KnowledgeBaseListService.class);
    var properties = new KnowledgeBaseQueryProperties();
    var assembler = new RagContextAssembler(repository, names, properties, mock(RagMetrics.class));
    String parent = "d680d0b3-1200-4ec7-89d5-26a2ed58de11";
    when(names.getAccessibleKnowledgeBaseNames(anyList())).thenReturn(Map.of(1L, "资料"));
    when(repository.findNeighbors(anyList())).thenReturn(List.of(
        new VectorRepository.NeighborChunk("neighbor", "条件", metadata(parent, 0))));
    var hit = new Document("hit", "结论", metadata(parent, 1));
    var logger = (Logger) LoggerFactory.getLogger(RagContextAssembler.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      assembler.assemble(List.of(hit), Set.of(1L));
      assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("RAG_CONTEXT_DIAGNOSTIC"));
      properties.getContext().setDiagnosticsEnabled(true);
      assembler.assemble(List.of(hit), Set.of(1L));
      assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
          .contains("RAG_CONTEXT_DIAGNOSTIC", "hit", "neighbor", "条件", "结论", "资料"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("邻居和名称查询异常时保留授权直接命中并使用快照")
  void degradesToDirectHitOnLookupFailure() {
    var repository = mock(VectorRepository.class);
    var names = mock(KnowledgeBaseListService.class);
    var metrics = mock(RagMetrics.class);
    when(names.getAccessibleKnowledgeBaseNames(anyList())).thenThrow(new IllegalStateException("不可用"));
    when(repository.findNeighbors(anyList())).thenThrow(new IllegalStateException("不可用"));
    var assembler = new RagContextAssembler(repository, names, new KnowledgeBaseQueryProperties(), metrics);
    var result = assembler.assemble(List.of(new Document("hit", "结论",
        metadata("d680d0b3-1200-4ec7-89d5-26a2ed58de11", 1))), Set.of(1L));
    assertThat(result.status()).isEqualTo(RagContextAssembler.Status.READY);
    assertThat(result.text()).contains("资料", "结论");
    assertThat(result.neighbors()).isEmpty();
    verify(metrics).recordContext(1, 0, 0, 1);
  }

  @Test
  @DisplayName("未授权命中被丢弃，重复邻居不被采用")
  void rejectsUnauthorizedHitsAndAmbiguousNeighbors() {
    var repository = mock(VectorRepository.class);
    var names = mock(KnowledgeBaseListService.class);
    var metrics = mock(RagMetrics.class);
    var properties = new KnowledgeBaseQueryProperties();
    String parent = "d680d0b3-1200-4ec7-89d5-26a2ed58de11";
    var neighbor = new VectorRepository.NeighborChunk("neighbor", "条件", metadata(parent, 0));
    when(repository.findNeighbors(anyList())).thenReturn(List.of(neighbor, neighbor));
    var assembler = new RagContextAssembler(repository, names, properties, metrics);
    var result = assembler.assemble(List.of(
        new Document("hit", "结论", metadata(parent, 1)),
        new Document("other", "私有资料", Map.of("kb_id", "2"))), Set.of(1L));
    assertThat(result.text()).contains("结论").doesNotContain("条件", "私有资料");
    assertThat(result.neighbors()).isEmpty();
    verify(metrics).recordContext(1, 0, 0, 1);
  }

  @Test
  @DisplayName("超预算时不截断正文，继续尝试短命中")
  void skipsOversizedHitAndKeepsShortHit() {
    var repository = mock(VectorRepository.class);
    var names = mock(KnowledgeBaseListService.class);
    var metrics = mock(RagMetrics.class);
    var properties = new KnowledgeBaseQueryProperties();
    properties.getContext().setMaxTokens(1024);
    var assembler = new RagContextAssembler(repository, names, properties, metrics);
    var result = assembler.assemble(List.of(
        new Document("large", "内容".repeat(10000), Map.of("kb_id", "1")),
        new Document("small", "短内容", Map.of("kb_id", "1"))), Set.of(1L));
    assertThat(result.status()).isEqualTo(RagContextAssembler.Status.READY);
    assertThat(result.directHits()).extracting(Document::getId).containsExactly("small");
    assertThat(result.text()).contains("短内容").doesNotContain("内容内容内容");
  }

  @Test
  @DisplayName("命中后补回同代邻居并按原文顺序排列")
  void expandsNeighbors() {
    var repository = mock(VectorRepository.class);
    var names = mock(KnowledgeBaseListService.class);
    var metrics = mock(RagMetrics.class);
    var properties = new KnowledgeBaseQueryProperties();
    String parent = "d680d0b3-1200-4ec7-89d5-26a2ed58de11";
    Map<String, Object> hitMetadata = metadata(parent, 1);
    Map<String, Object> previousMetadata = metadata(parent, 0);
    when(names.getAccessibleKnowledgeBaseNames(anyList())).thenReturn(Map.of(1L, "资料"));
    when(repository.findNeighbors(anyList())).thenReturn(List.of(
        new VectorRepository.NeighborChunk("00000000-0000-0000-0000-000000000001", "条件", previousMetadata)));
    var assembler = new RagContextAssembler(repository, names, properties, metrics);
    var result = assembler.assemble(List.of(new Document("00000000-0000-0000-0000-000000000002",
        "结论", hitMetadata)), Set.of(1L));
    assertThat(result.status()).isEqualTo(RagContextAssembler.Status.READY);
    assertThat(result.text()).contains("条件", "结论");
    assertThat(result.text().indexOf("条件")).isLessThan(result.text().indexOf("结论"));
    assertThat(result.directHits()).hasSize(1);
    assertThat(result.neighbors()).hasSize(1);
  }

  @Test
  @DisplayName("跨资料、跨代次和越界邻居不会进入上下文")
  void rejectsNeighborsOutsideRequestedDocumentAndGeneration() {
    var repository = mock(VectorRepository.class);
    var names = mock(KnowledgeBaseListService.class);
    var assembler = new RagContextAssembler(repository, names, new KnowledgeBaseQueryProperties(),
        mock(RagMetrics.class));
    String parent = "d680d0b3-1200-4ec7-89d5-26a2ed58de11";
    String otherParent = "d680d0b3-1200-4ec7-89d5-26a2ed58de12";
    var otherDocument = new java.util.HashMap<>(metadata(parent, 0));
    otherDocument.put("kb_id", "2");
    when(repository.findNeighbors(anyList())).thenReturn(List.of(
        new VectorRepository.NeighborChunk("other-document", "其他资料", otherDocument),
        new VectorRepository.NeighborChunk("other-generation", "其他代次", metadata(otherParent, 0)),
        new VectorRepository.NeighborChunk("out-of-range", "越界内容", metadata(parent, 2))));

    var result = assembler.assemble(List.of(new Document("hit", "结论", metadata(parent, 1))),
        Set.of(1L, 2L));

    assertThat(result.status()).isEqualTo(RagContextAssembler.Status.READY);
    assertThat(result.text()).contains("结论").doesNotContain("其他资料", "其他代次", "越界内容");
    assertThat(result.neighbors()).isEmpty();
  }

  @Test
  @DisplayName("全部命中未授权或超过预算时返回对应状态")
  void distinguishesNoAuthorizedHitsFromOversizedHits() {
    var repository = mock(VectorRepository.class);
    var names = mock(KnowledgeBaseListService.class);
    var properties = new KnowledgeBaseQueryProperties();
    properties.getContext().setMaxTokens(1024);
    var assembler = new RagContextAssembler(repository, names, properties, mock(RagMetrics.class));
    var hit = new Document("hit", "内容".repeat(10000), Map.of("kb_id", "1"));

    assertThat(assembler.assemble(List.of(hit), Set.of(2L)).status())
        .isEqualTo(RagContextAssembler.Status.NO_HITS);
    assertThat(assembler.assemble(List.of(hit), Set.of(1L)).status())
        .isEqualTo(RagContextAssembler.Status.TOO_LONG);
    verify(repository, org.mockito.Mockito.never()).findNeighbors(anyList());
  }

  private Map<String, Object> metadata(String parent, int index) {
    return Map.of("kb_id", "1", "chunk_strategy", "section-neighbor-v2",
        "parent_document_id", parent, "chunk_index", index, "total_chunks", 2,
        "section_path", "章节", "document_title", "资料");
  }
}
