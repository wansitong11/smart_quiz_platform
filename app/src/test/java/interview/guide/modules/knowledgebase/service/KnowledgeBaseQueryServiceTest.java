package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.model.RagQueryTraceEntity;
import interview.guide.modules.knowledgebase.service.scope.ResolvedScope;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseQueryServiceTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private RagMetrics ragMetrics;
  @Mock
  private KnowledgeBaseVectorService vectorService;
  @Mock
  private KnowledgeBaseListService listService;
  @Mock
  private KnowledgeBaseCountService countService;
  @Mock
  private ResourceLoader resourceLoader;
  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private ChatClient plainChatClient;
  @Mock
  private OwnerScope ownerScope;
  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock
  private RagContextAssembler contextAssembler;
  @Mock
  private RagQueryTraceService traceService;

  private KnowledgeBaseQueryService service;

  @BeforeEach
  void setUp() throws Exception {
    when(resourceLoader.getResource(anyString()))
        .thenAnswer(invocation -> new ByteArrayResource("模板".getBytes(StandardCharsets.UTF_8)));
    org.mockito.Mockito.lenient().when(knowledgeBaseRepository.findAccessibleIdsByVectorStatus(
        org.mockito.ArgumentMatchers.any(), eq(VectorStatus.COMPLETED))).thenReturn(List.of(1L));
  }

  private KnowledgeBaseQueryService buildService(boolean rewriteEnabled) throws Exception {
    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    properties.getRewrite().setEnabled(rewriteEnabled);
    properties.getSearch().setCiteSources(false);
    return new KnowledgeBaseQueryService(
        llmProviderRegistry,
        ragMetrics,
        vectorService,
        listService,
        countService,
        properties,
        resourceLoader,
        null,
        null,
        null,
        null,
        ownerScope,
        knowledgeBaseRepository,
        contextAssembler
    );
  }

  private void mockPlainClient() {
    when(llmProviderRegistry.getPlainChatClient()).thenReturn(plainChatClient);
  }

  private void stubDocuments() {
    when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
        .thenReturn(List.of(new Document("doc", "相关片段内容", Map.of("kb_id", 1L))));
  }

  private KnowledgeBaseQueryService buildSectionService() throws Exception {
    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    properties.setSectionNeighborEnabled(true);
    properties.getRewrite().setEnabled(false);
    properties.getSearch().setCiteSources(false);
    properties.getTools().setEnabled(false);
    return new KnowledgeBaseQueryService(llmProviderRegistry, ragMetrics, vectorService, listService,
        countService, properties, resourceLoader, null, traceService, null, null,
        ownerScope, knowledgeBaseRepository, contextAssembler);
  }

  @Test
  @DisplayName("四条问答入口遇到超长上下文均不调用回答模型")
  void allEntrancesRejectOversizedContext() throws Exception {
    service = buildSectionService();
    stubDocuments();
    when(contextAssembler.assemble(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anySet()))
        .thenReturn(new RagContextAssembler.ContextResult(RagContextAssembler.Status.TOO_LONG,
            "", List.of(), List.of(), List.of(), 0));
    ResolvedScope scope = ResolvedScope.ofSelection(List.of(1L));
    assertThat(service.answerQuestion(scope, "问题")).contains("资料片段过长");
    List<RagQueryExecution> traces = new ArrayList<>();
    assertThat(service.answerQuestionStream(scope, "问题", List.of(), traces::add).collectList().block())
        .singleElement().asString().contains("资料片段过长");
    assertThat(traces.getFirst().outcome()).isEqualTo("CONTEXT_TOO_LONG");
    assertThat(service.answerQuestionStreamWithTools(scope, 1L, "问题", List.of(), null)
        .collectList().block()).singleElement().asString().contains("资料片段过长");
    assertThat(service.searchKnowledge("问题", scope).status()).isEqualTo(RagContextAssembler.Status.TOO_LONG);
    assertThat(service.retrieveOnly(List.of(1L), "问题", List.of()).outcome())
        .isEqualTo("CONTEXT_TOO_LONG");
    var collector = org.mockito.ArgumentCaptor.forClass(RagQueryTraceCollector.class);
    verify(traceService).recordAsync(collector.capture());
    assertThat(collector.getValue().outcome()).isEqualTo(RagQueryTraceEntity.OUTCOME_CONTEXT_TOO_LONG);
    verify(llmProviderRegistry, never()).getPlainChatClient();
    verify(countService, never()).updateQuestionCounts(anyList());
  }

  @Test
  @DisplayName("新策略流式问答与检索测评使用同一组装上下文")
  void streamAndRetrievalUseAssembledContext() throws Exception {
    service = buildSectionService();
    stubDocuments();
    mockPlainClient();
    Document direct = new Document("doc", "相关片段内容", Map.of("kb_id", 1L));
    Document neighbor = new Document("neighbor", "前置条件", Map.of("kb_id", 1L));
    when(contextAssembler.assemble(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anySet()))
        .thenReturn(new RagContextAssembler.ContextResult(RagContextAssembler.Status.READY,
            "前置条件\n相关片段内容", List.of(direct), List.of(neighbor), List.of(1L), 10));
    when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
        .thenReturn(Flux.just("回答"));
    when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
        .thenReturn("同步回答");
    assertThat(service.answerQuestion(List.of(1L), "问题")).isEqualTo("同步回答");
    List<RagQueryExecution> traces = new ArrayList<>();
    service.answerQuestionStream(List.of(1L), "问题", List.of(), traces::add).collectList().block();
    RagQueryExecution retrieval = service.retrieveOnly(List.of(1L), "问题", List.of());
    assertThat(traces.getFirst().contextText()).isEqualTo("前置条件\n相关片段内容");
    assertThat(traces.getFirst().directDocumentIds()).containsExactly("doc");
    assertThat(traces.getFirst().neighborDocumentIds()).containsExactly("neighbor");
    assertThat(retrieval.contextText()).isEqualTo(traces.getFirst().contextText());
    assertThat(retrieval.neighborDocumentIds()).containsExactly("neighbor");
    assertThat(service.searchKnowledge("问题", ResolvedScope.ofSelection(List.of(1L))).text())
        .isEqualTo(retrieval.contextText());
  }

  @Test
  @DisplayName("未命中时同步、普通流式和工具检索不调用回答模型")
  void noHitsDoesNotCallAnswerModel() throws Exception {
    service = buildSectionService();
    when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble())).thenReturn(List.of());
    ResolvedScope scope = ResolvedScope.ofSelection(List.of(1L));
    assertThat(service.answerQuestion(scope, "问题")).contains("未检索到");
    assertThat(service.answerQuestionStream(scope, "问题", List.of()).collectList().block())
        .singleElement().asString().contains("未检索到");
    assertThat(service.searchKnowledge("问题", scope).status()).isEqualTo(RagContextAssembler.Status.NO_HITS);
    verify(llmProviderRegistry, never()).getPlainChatClient();
  }

  @Test
  @DisplayName("工具化预检索向模型注入组装结果并只计直接命中")
  void toolPreRetrievalUsesAssembledContext() throws Exception {
    when(resourceLoader.getResource(anyString()))
        .thenAnswer(invocation -> new ByteArrayResource(
            (invocation.getArgument(0).toString().contains("agent-user") ? "{context} {question}" : "模板")
                .getBytes(StandardCharsets.UTF_8)));
    service = buildSectionService();
    stubDocuments();
    Document direct = new Document("doc", "结论", Map.of("kb_id", 1L));
    Document neighbor = new Document("neighbor", "条件", Map.of("kb_id", 1L));
    when(contextAssembler.assemble(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anySet()))
        .thenReturn(new RagContextAssembler.ContextResult(RagContextAssembler.Status.READY,
            "条件\n结论", List.of(direct), List.of(neighbor), List.of(1L), 5));
    mockPlainClient();
    var promptSpec = mock(ChatClient.ChatClientRequestSpec.class, Answers.RETURNS_DEEP_STUBS);
    when(plainChatClient.prompt().system(anyString())).thenReturn(promptSpec);
    when(promptSpec.user(anyString()).stream().content()).thenReturn(Flux.just("回答"));
    assertThat(service.answerQuestionStreamWithTools(ResolvedScope.ofSelection(List.of(1L)), null,
        "问题", List.of(), null).collectList().block()).containsExactly("回答");
    var collector = org.mockito.ArgumentCaptor.forClass(RagQueryTraceCollector.class);
    verify(traceService).recordAsync(collector.capture());
    assertThat(collector.getValue().directHitCounts()).containsExactlyEntriesOf(Map.of(1L, 1));
    verify(promptSpec).user(org.mockito.ArgumentMatchers.<String>argThat(
        prompt -> prompt != null && prompt.replace("\r\n", "\n").contains("条件\n结论")));
    verify(countService).updateQuestionCounts(List.of(1L));
  }

  @Test
  @DisplayName("授权范围为空时不访问向量库")
  void doesNotSearchWhenNoAccessibleKnowledgeBases() throws Exception {
    when(knowledgeBaseRepository.findAccessibleIdsByVectorStatus(
        org.mockito.ArgumentMatchers.any(), eq(VectorStatus.COMPLETED))).thenReturn(List.of());
    service = buildService(false);
    assertThat(service.answerQuestion(List.of(1L), "问题")).contains("未检索到");
    verify(vectorService, never()).similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
  }

  @Test
  @DisplayName("流式轨迹记录实际发送模型的上下文")
  void traceContainsActualLegacyContext() throws Exception {
    service = buildService(false);
    mockPlainClient();
    stubDocuments();
    when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
        .thenReturn(Flux.just("回答"));
    List<RagQueryExecution> traces = new ArrayList<>();
    service.answerQuestionStream(List.of(1L), "问题", List.of(), traces::add).collectList().block();
    assertThat(traces.getFirst().contextText()).isEqualTo("相关片段内容");
    assertThat(traces.getFirst().directDocumentIds()).containsExactly("doc");
    assertThat(traces.getFirst().neighborDocumentIds()).isEmpty();
  }

  @Nested
  @DisplayName("ChatClient 选择")
  class ChatClientSelection {

    @Test
    @DisplayName("同步问答使用 Plain ChatClient 而非默认 ChatClient")
    void shouldUsePlainChatClientForSyncAnswer() throws Exception {
      service = buildService(false);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      String answer = service.answerQuestion(List.of(1L), "什么是 Java 内存模型");

      assertThat(answer).isEqualTo("同步回答");
      verify(llmProviderRegistry, atLeastOnce()).getPlainChatClient();
      verify(llmProviderRegistry, never()).getDefaultChatClient();
    }

    @Test
    @DisplayName("流式问答使用 Plain ChatClient 而非默认 ChatClient")
    void shouldUsePlainChatClientForStreamAnswer() throws Exception {
      service = buildService(false);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("流式回答"));

      List<String> chunks =
          service.answerQuestionStream(List.of(1L), "什么是 Java 内存模型").collectList().block();

      assertThat(chunks).containsExactly("流式回答");
      verify(llmProviderRegistry, atLeastOnce()).getPlainChatClient();
      verify(llmProviderRegistry, never()).getDefaultChatClient();
    }
  }

  @Test
  @DisplayName("带轨迹收集器的流式入口输出完整执行轨迹且不影响答案")
  void shouldEmitExecutionTraceForStream() throws Exception {
    service = buildService(false);
    mockPlainClient();
    stubDocuments();
    when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
        .thenReturn(Flux.just("流式回答"));
    List<interview.guide.modules.knowledgebase.service.RagQueryExecution> traces = new ArrayList<>();

    List<String> chunks = service
        .answerQuestionStream(List.of(1L), "什么是 Java 内存模型", List.of(), traces::add)
        .collectList().block();

    assertThat(chunks).containsExactly("流式回答");
    assertThat(traces).hasSize(1);
    interview.guide.modules.knowledgebase.service.RagQueryExecution execution = traces.getFirst();
    assertThat(execution.outcome()).isEqualTo("ANSWERED");
    assertThat(execution.answer()).isEqualTo("流式回答");
    assertThat(execution.retrievedDocs()).hasSize(1);
    assertThat(execution.rewrittenQuestion()).isEqualTo("什么是 Java 内存模型");
    assertThat(execution.rewriteDurationMs()).isGreaterThanOrEqualTo(0);
    assertThat(execution.retrievalDurationMs()).isGreaterThanOrEqualTo(0);
    assertThat(execution.generationDurationMs()).isGreaterThanOrEqualTo(0);
  }

  @Nested
  @DisplayName("业务指标埋点")
  class MetricsInstrumentation {

    private SimpleMeterRegistry meterRegistry;

    private KnowledgeBaseQueryService buildMetricsService() throws Exception {
      meterRegistry = new SimpleMeterRegistry();
      @SuppressWarnings("unchecked")
      org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
          mock(org.springframework.beans.factory.ObjectProvider.class);
      when(provider.getIfAvailable()).thenReturn(meterRegistry);
      KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
      properties.setMetricsEnabled(true);
      RagMetrics ragMetrics = new RagMetrics(provider, properties);
      return new KnowledgeBaseQueryService(llmProviderRegistry, ragMetrics, vectorService, listService,
          countService, properties, resourceLoader, null, null, null, null,
          ownerScope, knowledgeBaseRepository, contextAssembler);
    }

    @Test
    @DisplayName("同步成功：requests(success) 恰好一次，含全阶段 Timer")
    void syncSuccessRecordsOnce() throws Exception {
      service = buildMetricsService();
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      service.answerQuestion(List.of(1L), "什么是 Java 内存模型");

      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().count()).isEqualTo(1.0);
      meterRegistry.get(RagMetrics.REQUESTS).counter();
      assertThat(meterRegistry.get(RagMetrics.STAGE_DURATION).timers())
          .extracting(t -> t.getId().getTag("stage"))
          .contains("rewrite", "retrieve", "generate", "total");
    }

    @Test
    @DisplayName("同步无效请求：reject + refusal(invalid_request) 只记一次")
    void syncInvalidRequestRecordsReject() throws Exception {
      service = buildMetricsService();

      service.answerQuestion(List.of(1L), "   ");

      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().count()).isEqualTo(1.0);
      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().getId().getTag("result")).isEqualTo("reject");
      assertThat(meterRegistry.get(RagMetrics.REFUSALS).counter().count()).isEqualTo(1.0);
      assertThat(meterRegistry.get(RagMetrics.REFUSALS).counter().getId().getTag("reason"))
          .isEqualTo("invalid_request");
    }

    @Test
    @DisplayName("同步无命中：reject + refusal(no_hit)")
    void syncNoHitRecordsReject() throws Exception {
      service = buildMetricsService();
      mockPlainClient();
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of());

      service.answerQuestion(List.of(1L), "什么是 Java 内存模型");

      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().getId().getTag("result").equals("reject"))
          .isTrue();
      assertThat(meterRegistry.get(RagMetrics.REFUSALS).counter().getId().getTag("reason").equals("no_hit"))
          .isTrue();
    }

    @Test
    @DisplayName("同步异常：error 只记一次且不记 refusals")
    void syncErrorRecordsOnce() throws Exception {
      service = buildMetricsService();
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenThrow(new IllegalStateException("llm down"));

      assertThatThrownBy(() -> service.answerQuestion(List.of(1L), "什么是 Java 内存模型"))
          .isInstanceOf(interview.guide.common.exception.BusinessException.class);

      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().count()).isEqualTo(1.0);
      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().getId().getTag("result")).isEqualTo("error");
      assertThat(meterRegistry.find(RagMetrics.REFUSALS).counter()).isNull();
    }

    @Test
    @DisplayName("同步计数异常（检索之后归位计数）：仍记录 error 与已发生的阶段，且只记一次")
    void syncCountFailureStillRecordsError() throws Exception {
      service = buildMetricsService();
      stubDocuments();
      org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
          .when(countService).updateQuestionCounts(anyList());

      assertThatThrownBy(() -> service.answerQuestion(List.of(1L), "什么是 Java 内存模型"))
          .isInstanceOf(interview.guide.common.exception.BusinessException.class);

      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().count()).isEqualTo(1.0);
      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().getId().getTag("result"))
          .isEqualTo("error");
      assertThat(meterRegistry.get(RagMetrics.STAGE_DURATION).timers())
          .extracting(timer -> timer.getId().getTag("stage"))
          .containsExactlyInAnyOrder("rewrite", "retrieve", "total");
    }

    @Test
    @DisplayName("同步检索异常：仍记录 error、rewrite、retrieve 与 total")
    void syncRetrievalFailureStillRecordsError() throws Exception {
      service = buildMetricsService();
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenThrow(new IllegalStateException("vector down"));

      assertThatThrownBy(() -> service.answerQuestion(List.of(1L), "什么是 Java 内存模型"))
          .isInstanceOf(interview.guide.common.exception.BusinessException.class);

      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().count()).isEqualTo(1.0);
      assertThat(meterRegistry.get(RagMetrics.STAGE_DURATION).timers())
          .extracting(timer -> timer.getId().getTag("stage"))
          .contains("rewrite", "retrieve", "total");
    }

    @Test
    @DisplayName("流式完成：success 恰好一次")
    void streamCompleteRecordsOnce() throws Exception {
      service = buildMetricsService();
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("流式回答"));

      service.answerQuestionStream(List.of(1L), "什么是 Java 内存模型")
          .collectList().block();

      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().count()).isEqualTo(1.0);
      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().getId().getTag("result")).isEqualTo("success");
    }

    @Test
    @DisplayName("流式 error 被 onErrorResume 吞掉后 complete 不重复计数")
    void streamErrorRecordsOnceDespiteErrorResume() throws Exception {
      service = buildMetricsService();
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.error(new IllegalStateException("llm down")));

      List<String> chunks = service.answerQuestionStream(List.of(1L), "什么是 Java 内存模型")
          .collectList().block();

      assertThat(chunks).isNotEmpty();  // onErrorResume 返回错误文案
      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().count()).isEqualTo(1.0);
      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().getId().getTag("result")).isEqualTo("error");
    }

    @Test
    @DisplayName("流式取消：cancel 只记一次")
    void streamCancelRecordsOnce() throws Exception {
      service = buildMetricsService();
      mockPlainClient();
      stubDocuments();
      // 订阅后主动 dispose 模拟客户端断开，触发 doOnCancel
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("chunk").delayElements(java.time.Duration.ofSeconds(30)));

      reactor.core.Disposable subscription = service.answerQuestionStream(List.of(1L), "什么是 Java 内存模型")
          .subscribe();
      Thread.sleep(500);
      subscription.dispose();

      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().getId().getTag("result")).isEqualTo("cancel");
      assertThat(meterRegistry.get(RagMetrics.REQUESTS).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("改写失败回退：rewrite.fallbacks(error) 计数")
    void rewriteFallbackOnError() throws Exception {
      service = buildMetricsService();
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenThrow(new IllegalStateException("llm down"));
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      service.answerQuestion(List.of(1L), "什么是 Java 内存模型");

      assertThat(meterRegistry.get(RagMetrics.REWRITE_FALLBACKS).counter().count()).isEqualTo(1.0);
      assertThat(meterRegistry.get(RagMetrics.REWRITE_FALLBACKS).counter().getId().getTag("reason"))
          .isEqualTo("error");
    }

    @Test
    @DisplayName("retrieveOnly 只记检索指标，不记 requests/refusals")
    void retrieveOnlySkipsRequestCounters() throws Exception {
      service = buildMetricsService();
      mockPlainClient();
      stubDocuments();

      service.retrieveOnly(List.of(1L), "什么是 Java 内存模型", List.of());

      assertThat(meterRegistry.find(RagMetrics.REQUESTS).counter()).isNull();
      assertThat(meterRegistry.find(RagMetrics.REFUSALS).counter()).isNull();
      assertThat(meterRegistry.get(RagMetrics.STAGE_DURATION).timers()).isNotEmpty();
    }

    @Test
    @DisplayName("全部 Meter 的标签键白名单：mode/result/stage/variant/reason")
    void tagKeysWhitelisted() throws Exception {
      service = buildMetricsService();
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      service.answerQuestion(List.of(1L), "什么是 Java 内存模型");
      service.retrieveOnly(List.of(1L), "另一个问题", List.of());

      meterRegistry.getMeters().forEach(meter ->
          assertThat(meter.getId().getTags()).allSatisfy(tag ->
              assertThat(tag.getKey()).isIn("mode", "result", "stage", "variant", "reason")));
    }
  }

  @Nested
  @DisplayName("双路召回融合")
  class DualPathMerge {

    private org.springframework.ai.document.Document doc(String id, Double score) {
      return org.springframework.ai.document.Document.builder()
          .id(id).text("片段-" + id).score(score).metadata(Map.of("kb_id", 1L)).build();
    }

    private KnowledgeBaseQueryService buildMergeService() throws Exception {
      KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
      properties.getSearch().setMergeOriginalQuery(true);
      return new KnowledgeBaseQueryService(llmProviderRegistry, ragMetrics, vectorService, listService,
          countService, properties, resourceLoader, null, null, null, null,
          ownerScope, knowledgeBaseRepository, contextAssembler);
    }

    private List<RagQueryExecution> answerCollect(KnowledgeBaseQueryService svc, String question)
        throws Exception {
      List<RagQueryExecution> traces = new ArrayList<>();
      svc.answerQuestionStream(List.of(1L), question, List.of(), traces::add)
          .collectList().block();
      return traces;
    }

    @Test
    @DisplayName("改写结果等于原问题：只调用一次向量检索")
    void shouldSearchOnceWhenRewriteEqualsOriginal() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("什么是 HashMap");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("d1", 0.9)));

      answerCollect(service, "什么是 HashMap");

      verify(vectorService, times(1)).similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("改写结果与原问题不同：调用两次向量检索")
    void shouldSearchTwiceWhenRewriteDiffers() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写后的 HashMap 原理问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("d1", 0.9)));

      answerCollect(service, "什么是 HashMap");

      verify(vectorService, times(2)).similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("两路命中相同 Document ID：去重保留一条且分数取更高值")
    void shouldDedupByDocumentIdKeepHigherScore() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(eq("改写问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("shared", 0.6), doc("r1", 0.5)));
      when(vectorService.similaritySearch(eq("原始问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("shared", 0.8)));

      List<RagQueryExecution> traces = answerCollect(service, "原始问题");

      List<RagQueryExecution.RetrievedDoc> docs = traces.getFirst().retrievedDocs();
      assertThat(docs).extracting(RagQueryExecution.RetrievedDoc::text)
          .containsExactly("片段-shared", "片段-r1");
      assertThat(docs.getFirst().score()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("改写路弱结果、原始路正确结果：正确结果进入最终 Top K")
    void shouldKeepOriginalPathCorrectResult() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写跑偏的问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(eq("改写跑偏的问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("weak1", 0.3), doc("weak2", 0.29)));
      when(vectorService.similaritySearch(eq("正确问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("correct", 0.95)));

      List<RagQueryExecution> traces = answerCollect(service, "正确问题");

      assertThat(traces.getFirst().retrievedDocs().getFirst().text()).isEqualTo("片段-correct");
    }

    @Test
    @DisplayName("分数为 null：不抛异常且 null 分数排后有分数结果之后")
    void shouldSortNullScoresStable() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(eq("改写问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("nullA", null)));
      when(vectorService.similaritySearch(eq("原始问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("scored", 0.7), doc("nullB", null)));

      List<RagQueryExecution> traces = answerCollect(service, "原始问题");

      assertThat(traces.getFirst().retrievedDocs()).extracting(RagQueryExecution.RetrievedDoc::text)
          .containsExactly("片段-scored", "片段-nullA", "片段-nullB");
    }

    @Test
    @DisplayName("去重后条数超过 Top K 时按分数降序截断")
    void shouldTruncateToTopKAfterDedup() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      // 长问题走 topkLong=8：两路共 10 个不同 Document，最终应截为 8 个且分数最高在前
      String longQuestion = "这是一条足够长的原始问题用于触发长问题分档";
      when(vectorService.similaritySearch(eq("改写问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("a", 0.91), doc("b", 0.81), doc("c", 0.71), doc("d", 0.61), doc("e", 0.51)));
      when(vectorService.similaritySearch(eq(longQuestion), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("f", 0.86), doc("g", 0.76), doc("h", 0.66), doc("i", 0.56), doc("j", 0.46)));

      List<RagQueryExecution> traces = answerCollect(service, longQuestion);

      List<RagQueryExecution.RetrievedDoc> docs = traces.getFirst().retrievedDocs();
      assertThat(docs).hasSize(8);
      assertThat(docs).extracting(RagQueryExecution.RetrievedDoc::text)
          .containsExactly("片段-a", "片段-f", "片段-b", "片段-g", "片段-c", "片段-h", "片段-d", "片段-i");
    }

    @Test
    @DisplayName("功能开关关闭：保持首个有效结果行为（改写命中即返回）")
    void shouldKeepFirstHitBehaviorWhenDisabled() throws Exception {
      service = buildService(true);   // 默认配置 mergeOriginalQuery=false
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("d1", 0.9)));

      List<RagQueryExecution> traces = answerCollect(service, "原始问题");

      verify(vectorService, times(1)).similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
      assertThat(traces.getFirst().attemptedQueries()).containsExactly("改写问题");
    }
  }

  @Nested
  @DisplayName("日志隐私")
  class LogPrivacy {

    private final Logger queryServiceLogger =
        (Logger) LoggerFactory.getLogger(KnowledgeBaseQueryService.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUpAppender() {
      logAppender.start();
      queryServiceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownAppender() {
      queryServiceLogger.detachAppender(logAppender);
    }

    private String capturedLogs() {
      StringBuilder sb = new StringBuilder();
      for (ILoggingEvent event : logAppender.list) {
        sb.append(event.getFormattedMessage()).append('\n');
      }
      return sb.toString();
    }

    @Test
    @DisplayName("同步问答日志不包含问题与改写原文，只记录长度和命中数")
    void shouldNotLogQuestionOrRewrittenQuery() throws Exception {
      String questionMarker = "问题敏感标记MARKER-Q-8f21";
      String rewriteMarker = "改写敏感标记MARKER-R-3c55";
      service = buildService(true);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn(rewriteMarker);
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      service.answerQuestion(List.of(1L), questionMarker);

      String logs = capturedLogs();
      assertThat(logs).doesNotContain(questionMarker);
      assertThat(logs).doesNotContain(rewriteMarker);
      assertThat(logs).contains("questionLength=" + questionMarker.length());
      assertThat(logs).contains("rewrittenLength=" + rewriteMarker.length());
      assertThat(logs).contains("hits=1");
    }

    @Test
    @DisplayName("null 问题不抛 NPE 且返回无结果响应")
    void shouldReturnNoResultForNullQuestion() throws Exception {
      service = buildService(false);

      String answer = service.answerQuestion(List.of(1L), null);
      List<String> chunks = service.answerQuestionStream(List.of(1L), null).collectList().block();

      assertThat(answer).contains("未检索到相关信息");
      assertThat(chunks).hasSize(1);
      verify(llmProviderRegistry, never()).getPlainChatClient();
    }

    @Test
    @DisplayName("异常消息与 Throwable 渲染均不包含上游自由文本")
    void shouldRemoveExceptionMessageFromLogs() throws Exception {
      String tail = "敏感尾部标记MARKER-ERR-TAIL-1a2b3c4d5e6f";
      service = buildService(true);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenThrow(new IllegalStateException("x".repeat(500) + tail));
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      service.answerQuestion(List.of(1L), "正常问题");

      String logs = capturedLogs();
      assertThat(logs).doesNotContain(tail);
      assertThat(logs).contains("IllegalStateException");
      ILoggingEvent warnEvent = logAppender.list.stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .findFirst().orElseThrow();
      assertThat(warnEvent.getThrowableProxy().getMessage())
          .doesNotContain(tail)
          .contains("sanitized-error type=IllegalStateException");
    }

    @Test
    @DisplayName("改写失败回退时日志只保留错误类型且不包含问题原文")
    void shouldKeepErrorTypeWithoutQuestionText() throws Exception {
      String questionMarker = "问题敏感标记MARKER-Q-ERR-51d0";
      service = buildService(true);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenThrow(new IllegalStateException("LLM 连接超时"));
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      String answer = service.answerQuestion(List.of(1L), questionMarker);

      assertThat(answer).isEqualTo("同步回答");
      String logs = capturedLogs();
      assertThat(logs).doesNotContain(questionMarker);
      assertThat(logs).contains("Query rewrite 失败");
      assertThat(logs).doesNotContain("LLM 连接超时");
      assertThat(logs).contains("IllegalStateException");
      ILoggingEvent warnEvent = logAppender.list.stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .findFirst().orElse(null);
      assertThat(warnEvent).isNotNull();
      assertThat(warnEvent.getThrowableProxy()).isNotNull();
    }
  }

  @Nested
  @DisplayName("Query 改写")
  class QueryRewrite {

    @Test
    @DisplayName("改写开启时使用 Plain ChatClient 完成改写并检索")
    void shouldRewriteWithPlainChatClientWhenEnabled() throws Exception {
      service = buildService(true);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写后的问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      String answer = service.answerQuestion(List.of(1L), "jmm 是什么");

      assertThat(answer).isEqualTo("同步回答");
      verify(llmProviderRegistry, atLeastOnce()).getPlainChatClient();
      verify(llmProviderRegistry, never()).getDefaultChatClient();
    }

    @Test
    @DisplayName("改写关闭时不发起改写调用")
    void shouldSkipRewriteWhenDisabled() throws Exception {
      service = buildService(false);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      String answer = service.answerQuestion(List.of(1L), "什么是 Java 内存模型");

      assertThat(answer).isEqualTo("同步回答");
      verify(plainChatClient.prompt().user(anyString()), never()).call();
      verify(llmProviderRegistry, never()).getDefaultChatClient();
    }

    @Test
    @DisplayName("改写失败时回退原问题并正常回答")
    void shouldFallbackToOriginalQuestionWhenRewriteFails() throws Exception {
      service = buildService(true);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenThrow(new IllegalStateException("改写服务不可用"));
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      String answer = service.answerQuestion(List.of(1L), "什么是 Java 内存模型");

      assertThat(answer).isEqualTo("同步回答");
      verify(llmProviderRegistry, never()).getDefaultChatClient();
    }
  }
}
