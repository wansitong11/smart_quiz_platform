package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class RagEvidencePipelineTest {
  private final VectorRepository repository = mock(VectorRepository.class);
  private final KnowledgeBaseListService names = mock(KnowledgeBaseListService.class);
  private final KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
  private final RagContextAssembler assembler = new RagContextAssembler(
      repository, names, properties, mock(RagMetrics.class));
  private static final String PARENT = "d680d0b3-1200-4ec7-89d5-26a2ed58de11";

  @Test
  @DisplayName("先补邻再复核：窗口共享原文，保留后不再查库或扩展")
  void preservesCompleteWindowsWithoutDuplicateText() {
    when(names.getAccessibleKnowledgeBaseNames(anyList())).thenReturn(Map.of(4L, "数学"));
    when(repository.findNeighbors(anyList())).thenReturn(List.of(
        new VectorRepository.NeighborChunk("previous", "先算百分比", metadata(0))));
    var prepared = assembler.prepareEvidence(List.of(
        new Document("middle", "再算圆心角", metadata(1)),
        new Document("last", "最后涂色", metadata(2))), Set.of(4L), 20, true);
    assertThat(prepared.blocks()).hasSize(3);
    assertThat(prepared.units()).hasSize(2);
    assertThat(prepared.text()).contains("本批可引用证据编号：〔E1〕、〔E2〕", "不得将块编号改写成证据编号");
    assertThat(prepared.text().split("再算圆心角", -1)).hasSize(2);
    clearInvocations(repository, names);
    var retained = assembler.retainEvidence(prepared, Set.of("E1"));
    assertThat(retained.text()).contains("先算百分比", "再算圆心角", "最后涂色");
    assertThat(retained.units()).hasSize(1);
    verifyNoInteractions(repository, names);
  }

  @Test
  @DisplayName("未知保留编号不能静默放行，空集合正常无证据")
  void rejectsUnknownUnitAndAllowsEmpty() {
    var prepared = assembler.prepareEvidence(List.of(
        new Document("spring", "ChatClient", Map.of("kb_id", "23"))), Set.of(23L), 20, false);
    assertThatThrownBy(() -> assembler.retainEvidence(prepared, Set.of("E9")))
        .isInstanceOf(BusinessException.class);
    assertThat(assembler.retainEvidence(prepared, Set.of()).status())
        .isEqualTo(RagContextAssembler.Status.NO_HITS);
  }

  @Test
  @DisplayName("引用只认正文中的本轮有效编号，不统计代码与未引用资料")
  void citationsExcludeUnusedAndCode() {
    var result = RagEvidenceSession.scanCitations(
        "学习 ChatClient〔E1〕。`〔E2〕`\n```text\n〔E2〕\n```\n还有错误〔E99〕。",
        Set.of("E1", "E2"));
    assertThat(result.unitIds()).containsExactly("E1");
    assertThat(result.invalid()).isTrue();
  }

  @Test
  @DisplayName("完整原文及编号超预算时跳过，不截断规则")
  void boundsEntireProtocol() {
    properties.getContext().setMaxTokens(1024);
    var prepared = assembler.prepareEvidence(List.of(
        new Document("huge", "不得省略的例外".repeat(3000), Map.of("kb_id", "4")),
        new Document("small", "短规则", Map.of("kb_id", "4"))), Set.of(4L), 20, false);
    assertThat(prepared.tokens()).isLessThanOrEqualTo(1024);
    assertThat(prepared.blocks()).extracting(block -> block.document().getId())
        .containsExactly("small");
  }

  private Map<String, Object> metadata(int index) {
    return Map.of("kb_id", "4", "chunk_strategy", "section-neighbor-v2",
        "parent_document_id", PARENT, "chunk_index", index, "total_chunks", 3,
        "section_path", "统计图");
  }

  @Test
  @DisplayName("重叠窗口不递归合并整篇，同代次相邻的另一直接命中可作为必要邻居保留")
  void doesNotTransitivelyMerge() {
    when(repository.findNeighbors(anyList())).thenReturn(List.of(
        new VectorRepository.NeighborChunk("third", "不属于第一窗口", metadata(2))));
    var prepared = assembler.prepareEvidence(List.of(
        new Document("first", "第一块", metadata(0)),
        new Document("second", "第二块", metadata(1))), Set.of(4L), 20, true);
    var kept = assembler.retainEvidence(prepared, Set.of("E1"));
    assertThat(kept.text()).contains("第一块", "第二块").doesNotContain("不属于第一窗口");
    assertThat(kept.blocks()).hasSize(2);
  }

  @Test
  @DisplayName("累计预算已满时不重新扩容，不截断旧证据或新规则")
  void previousBudgetLimitsNewEvidence() {
    properties.getContext().setMaxTokens(1024);
    var previous = RagEvidenceSessionTest.prepared("previous", 4, "旧规则".repeat(250));
    var prepared = assembler.prepareEvidence(List.of(new Document("new", "新规则".repeat(250),
        Map.of("kb_id", "4"))), Set.of(4L), 20, false,
        new RagContextAssembler.BudgetSnapshot(previous.blocks(), previous.units()));
    assertThat(prepared.status()).isEqualTo(RagContextAssembler.Status.TOO_LONG);
    assertThat(prepared.blocks()).isEmpty();
  }
}
