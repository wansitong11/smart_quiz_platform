package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository.NeighborChunk;
import interview.guide.modules.knowledgebase.service.scope.ResolvedScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

@Timeout(15)
class KnowledgeBaseQueryServiceRelevanceTest {
  private KnowledgeBaseQueryProperties properties;
  private KnowledgeBaseVectorService vectorService;
  private KnowledgeBaseRepository repository;
  private LlmProviderRegistry registry;
  private ChatClient chatClient;
  private RagContextAssembler assembler;

  @BeforeEach
  void setUp() {
    properties = new KnowledgeBaseQueryProperties();
    properties.getRewrite().setEnabled(false);
    properties.getSearch().getRelevance().setEnabled(true);
    vectorService = mock(KnowledgeBaseVectorService.class);
    repository = mock(KnowledgeBaseRepository.class);
    registry = mock(LlmProviderRegistry.class);
    chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
    assembler = mock(RagContextAssembler.class);
    when(repository.findAccessibleIdsByVectorStatus(any(), any(VectorStatus.class)))
        .thenReturn(List.of(4L, 23L));
    when(registry.getPlainChatClient()).thenReturn(chatClient);
  }

  private KnowledgeBaseQueryService service() throws Exception {
    return new KnowledgeBaseQueryService(registry, mock(RagMetrics.class), vectorService,
        mock(KnowledgeBaseListService.class), mock(KnowledgeBaseCountService.class), properties,
        new DefaultResourceLoader(), null, mock(RagQueryTraceService.class), null, null,
        mock(OwnerScope.class), repository,
        assembler);
  }

  private void returnsCandidates(List<Document> candidates) {
    when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
        .thenReturn(candidates);
  }

  @Test
  @DisplayName("全部资料检索不应为保底把低分技术资料混入数学上下文")
  void excludesWeakUnrelatedSourceBeforeQuota() throws Exception {
    var candidates = new ArrayList<Document>();
    double[] scores = {.6824, .6128, .5595, .5341, .5220, .5123, .4926, .4900, .4850};
    for (int index = 0; index < scores.length; index++) {
      candidates.add(document("math-" + index, 4L, scores[index]));
    }
    candidates.add(document("spring", 23L, .4117));
    returnsCandidates(candidates);
    var service = service();

    var result = service.searchKnowledge("用不同颜色或条纹把各个扇形区别开是什么意思",
        ResolvedScope.ofSelection(List.of(4L, 23L)));

    assertThat(result.documents()).hasSize(8);
    assertThat(result.knowledgeBaseIds()).containsExactly(4L);
    assertThat(result.documents()).extracting(Document::getId).doesNotContain("spring");
  }

  @Test
  @DisplayName("整池低分不能互相保底，单库或少于名额也必须筛选")
  void rejectsLowPoolAndSmallPool() throws Exception {
    var service = service();
    assertThat(service.selectCandidateDocuments(List.of(document("a", 4, .39),
        document("b", 4, .38)), 8)).isEmpty();
    assertThat(service.selectCandidateDocuments(List.of(document("a", 4, .70),
        document("b", 4, .41)), 8)).extracting(Document::getId).containsExactly("a");
    assertThat(service.selectCandidateDocuments(null, 8)).isEmpty();
    assertThat(service.selectCandidateDocuments(List.of(), 8)).isEmpty();
  }

  @Test
  @DisplayName("门槛包含等号，按未舍入分数比较且同分排序稳定")
  void keepsInclusiveBoundariesAndStableTies() throws Exception {
    properties.getSearch().getRelevance().setMaxScoreDrop(.25);
    assertThat(service().filterRelevantCandidates(List.of(document("below", 4, .4999),
        document("equal1", 4, .50), document("top", 4, .75), document("equal2", 4, .50))))
        .extracting(Document::getId).containsExactly("top", "equal1", "equal2");
    assertThat(service().filterRelevantCandidates(List.of(document("min", 4, .40))))
        .hasSize(1);
    properties.getSearch().getRelevance().setMaxScoreDrop(0);
    assertThat(service().filterRelevantCandidates(List.of(document("a", 4, .75),
        document("b", 23, .75), document("c", 4, .74))))
        .extracting(Document::getId).containsExactly("a", "b");
    properties.getSearch().getRelevance().setMaxScoreDrop(1);
    assertThat(service().filterRelevantCandidates(List.of(document("a", 4, .90),
        document("b", 23, .40), document("c", 4, .39))))
        .extracting(Document::getId).containsExactly("a", "b");
  }

  @Test
  @DisplayName("只接受有限相似度，不从距离或自定义元数据猜分数")
  void rejectsInvalidScoresWithoutMetadataFallback() throws Exception {
    var candidates = new ArrayList<Document>();
    Double[] invalid = {null, Double.NaN, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, -.1, 1.1};
    for (int index = 0; index < invalid.length; index++) {
      candidates.add(Document.builder().id("invalid-" + index).text("无效")
          .metadata(Map.of("kb_id", 4L, "distance", .99, "score", .99))
          .score(invalid[index]).build());
    }
    assertThat(service().filterRelevantCandidates(candidates)).isEmpty();
    candidates.add(document("valid", 4, .7));
    assertThat(service().filterRelevantCandidates(candidates))
        .extracting(Document::getId).containsExactly("valid");
  }

  @Test
  @DisplayName("合格跨资料证据共同保留，不用弱候选补满名额")
  void preservesMultipleRelevantSourcesWithoutRefill() throws Exception {
    var selected = service().selectCandidateDocuments(List.of(document("a", 4, .75),
        document("b", 23, .72), document("a2", 4, .65), document("b2", 23, .60),
        document("weak", 99, .41)), 8);
    assertThat(selected).extracting(Document::getId).containsExactly("a", "b", "a2", "b2");
  }

  @Test
  @DisplayName("关闭筛选完全恢复原有保底选择，包括旧空分数候选")
  void disabledFilterPreservesLegacyBehavior() throws Exception {
    properties.getSearch().getRelevance().setEnabled(false);
    var service = service();
    var candidates = List.of(document("a", 4, .9), document("b", 23, .1),
        document("null", 23, null));
    assertThat(service.filterRelevantCandidates(candidates)).isSameAs(candidates);
    assertThat(service.selectCandidateDocuments(candidates, 2))
        .isEqualTo(service.balanceByKnowledgeBase(candidates, 2));
  }

  @Test
  @DisplayName("改写被筛空时回退原问题，有合格命中时不额外检索")
  void fallsBackOnlyAfterRewrittenCandidatesFailGate() throws Exception {
    properties.getRewrite().setEnabled(true);
    when(chatClient.prompt().user(anyString()).call().content()).thenReturn("改写问题");
    when(vectorService.similaritySearch(eq("改写问题"), anyList(), anyInt(), anyDouble()))
        .thenReturn(List.of(document("weak", 23, .39)));
    when(vectorService.similaritySearch(eq("原始问题"), anyList(), anyInt(), anyDouble()))
        .thenReturn(List.of(document("strong", 4, .7)));
    var result = service().retrieveOnly(List.of(4L, 23L), "原始问题", List.of());
    assertThat(result.directDocumentIds()).containsExactly("strong");
    assertThat(result.attemptedQueries()).containsExactly("改写问题", "原始问题");
    when(vectorService.similaritySearch(eq("改写问题"), anyList(), anyInt(), anyDouble()))
        .thenReturn(List.of(document("strong", 4, .7)));
    var direct = service().retrieveOnly(List.of(4L, 23L), "第二个原始问题", List.of());
    assertThat(direct.attemptedQueries()).containsExactly("改写问题");
    verify(vectorService, never()).similaritySearch(eq("第二个原始问题"), anyList(),
        anyInt(), anyDouble());
  }

  @Test
  @DisplayName("越权最高分先剔除，不影响用户自己资料的门槛")
  void authorizesBeforeComputingTopScore() throws Exception {
    returnsCandidates(List.of(document("forbidden", 99, .99), document("a", 4, .60),
        document("b", 23, .59)));
    assertThat(service().searchKnowledge("问题", ResolvedScope.ofSelection(List.of(4L, 23L)))
        .documents()).extracting(Document::getId).containsExactly("a", "b");
    verify(registry, never()).getPlainChatClient();
  }

  @Test
  @DisplayName("双路融合只计算一次门槛，同UUID非法分数不能覆盖有效分数")
  void filtersOnceAfterFusionAndRejectsInvalidDuplicates() throws Exception {
    properties.getRewrite().setEnabled(true);
    properties.getSearch().setMergeOriginalQuery(true);
    when(chatClient.prompt().user(anyString()).call().content()).thenReturn("改写");
    Document first = document("same", 4, .8);
    when(vectorService.similaritySearch(eq("改写"), anyList(), anyInt(), anyDouble()))
        .thenReturn(List.of(first, document("valid", 4, .75),
            document("infinite", 4, Double.POSITIVE_INFINITY)));
    when(vectorService.similaritySearch(eq("原问题"), anyList(), anyInt(), anyDouble()))
        .thenReturn(List.of(document("weak", 23, .55), document("weaker", 23, .54),
            document("same", 4, Double.NaN), document("valid", 4, Double.POSITIVE_INFINITY),
            document("infinite", 4, .7)));
    var result = service().retrieveOnly(List.of(4L, 23L), "原问题", List.of());
    assertThat(result.directDocumentIds()).containsExactly("same", "valid", "infinite");
  }

  @Test
  @DisplayName("同步流式和工具入口不能绕过筛选，工具化预检索为空仍遵循现有路由")
  void allEntrancesApplyGate() throws Exception {
    returnsCandidates(List.of(document("weak", 4, .39)));
    var service = service();
    var scope = ResolvedScope.ofSelection(List.of(4L));
    assertThat(service.answerQuestion(scope, "问题")).contains("未检索到");
    assertThat(service.answerQuestionStream(scope, "问题", List.of()).collectList()
        .block(Duration.ofSeconds(5)))
        .singleElement().asString().contains("未检索到");
    assertThat(service.searchKnowledge("问题", scope).documents()).isEmpty();
    assertThat(service.retrieveOnly(List.of(4L), "问题", List.of()).outcome()).isEqualTo("NO_RESULT");
    verify(registry, never()).getPlainChatClient();
    properties.getTools().setEnabled(false);
    when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
        .thenReturn(Flux.just("无法根据提供内容回答"));
    assertThat(service().answerQuestionStreamWithTools(scope, null, "问题", List.of(), null)
        .collectList().block(Duration.ofSeconds(5)))
        .singleElement().asString().contains("未检索到");
  }

  @Test
  @DisplayName("工具化预检索只注入合格片段，不把被拒绝片段发给回答模型")
  void toolPreRetrievalDoesNotInjectRejectedText() throws Exception {
    properties.getTools().setEnabled(false);
    properties.getSearch().setCiteSources(false);
    returnsCandidates(List.of(document("math-evidence", 4, .70),
        document("spring-rejected", 23, .41)));
    when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
        .thenReturn(Flux.just("资料中的结论"));
    assertThat(service().answerQuestionStreamWithTools(ResolvedScope.ofSelection(List.of(4L, 23L)),
        null, "问题", List.of(), null).collectList().block(Duration.ofSeconds(5)))
        .containsExactly("资料中的结论");
    var prompt = ArgumentCaptor.forClass(String.class);
    verify(chatClient.prompt().system(anyString()), atLeastOnce()).user(prompt.capture());
    assertThat(prompt.getAllValues().getLast()).contains("math-evidence")
        .doesNotContain("spring-rejected");
  }

  @Test
  @DisplayName("邻居没有分数仍保留，组装器只接收筛后的直接命中")
  void doesNotFilterUnscoredNeighbors() throws Exception {
    properties.setSectionNeighborEnabled(true);
    Document direct = document("direct", 4, .7);
    Document neighbor = document("neighbor", 4, null);
    returnsCandidates(List.of(direct, document("weak", 23, .41)));
    when(assembler.assemble(anyList(), any())).thenReturn(
        new RagContextAssembler.ContextResult(RagContextAssembler.Status.READY, "条件\n结论",
            List.of(direct), List.of(neighbor), List.of(4L), 5));
    var result = service().retrieveOnly(List.of(4L, 23L), "问题", List.of());
    assertThat(result.neighborDocumentIds()).containsExactly("neighbor");
    assertThat(result.contextText()).isEqualTo("条件\n结论");
    verify(assembler).assemble(eq(List.of(direct)), any());
  }

  @Test
  @DisplayName("真实日志分数最小重放：筛掉技术片段后仍能由生产组装器补齐四个步骤")
  void recordedScoreReplayPreservesActualNeighborAssembly() throws Exception {
    String parentId = "f6253463-b549-4ff0-b50c-9e52dddf25db";
    Map<String, Object> tailMetadata = Map.of("kb_id", 4L,
        "parent_document_id", parentId, "chunk_index", 35, "total_chunks", 36,
        "chunk_strategy", "section-neighbor-v2", "section_path", "",
        "document_title", "小学数学知识点汇总（1）");
    Map<String, Object> headMetadata = new HashMap<>(tailMetadata);
    headMetadata.put("chunk_index", 34);
    Document tail = Document.builder().id("321377ad-c1c7-45b1-b385-0e12c1e3a8ea")
        .metadata(tailMetadata).score(.6823827922344208)
        .text("（4）标明名称和百分数，并用不同颜色或条纹把各个扇形区别开。").build();
    Document unrelated = document("spring", 23, .4117138385772705);
    var selected = service().selectCandidateDocuments(List.of(tail, unrelated), 8);
    var neighbors = mock(VectorRepository.class);
    when(neighbors.findNeighbors(anyList())).thenReturn(List.of(new NeighborChunk(
        "810d9095-6d46-4e36-8754-9092f8323eca",
        "（1）先算百分数。（2）再算圆心角。（3）画圆和各个扇形。", headMetadata)));
    var names = mock(KnowledgeBaseListService.class);
    when(names.getAccessibleKnowledgeBaseNames(anyList())).thenReturn(Map.of(4L, "小学数学"));
    var productionAssembler = new RagContextAssembler(neighbors, names, properties,
        mock(RagMetrics.class));

    var context = productionAssembler.assemble(selected, Set.of(4L, 23L));

    assertThat(context.directHits()).extracting(Document::getId).containsExactly(tail.getId());
    assertThat(context.neighbors()).hasSize(1).allSatisfy(document ->
        assertThat(document.getScore()).isNull());
    assertThat(context.text()).contains("（1）", "（2）", "（3）", "（4）")
        .doesNotContain("spring");
    assertThat(context.text().indexOf("（1）")).isLessThan(context.text().indexOf("（4）"));
    assertThat(context.tokens()).isLessThanOrEqualTo(6000);
  }

  @Test
  @DisplayName("改写异常和空白回退不会关闭相关性筛选")
  void rewriteFailureAndBlankStillApplyGate() throws Exception {
    properties.getRewrite().setEnabled(true);
    returnsCandidates(List.of(document("weak", 4, .39)));
    when(chatClient.prompt().user(anyString()).call().content())
        .thenThrow(new IllegalStateException("模拟改写错误")).thenReturn("  ");
    assertThat(service().retrieveOnly(List.of(4L), "问题", List.of()).outcome())
        .isEqualTo("NO_RESULT");
    assertThat(service().retrieveOnly(List.of(4L), "问题", List.of()).outcome())
        .isEqualTo("NO_RESULT");
  }

  private static Document document(String id, long kbId, Double score) {
    return Document.builder().id(id).text("片段 " + id).metadata(Map.of("kb_id", kbId))
        .score(score).build();
  }
}
