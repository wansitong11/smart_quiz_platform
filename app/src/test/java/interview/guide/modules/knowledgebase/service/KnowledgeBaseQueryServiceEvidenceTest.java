package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.service.RagEvidenceReviewService.ReviewDecision;
import interview.guide.modules.knowledgebase.service.scope.ResolvedScope;
import interview.guide.modules.knowledgebase.service.tool.RagKnowledgeSearchTool;
import interview.guide.modules.knowledgebase.service.tool.RagToolContextKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;

class KnowledgeBaseQueryServiceEvidenceTest {
  private final LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
  private final StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
  private final KnowledgeBaseVectorService vectors = mock(KnowledgeBaseVectorService.class);
  private final KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
  private final KnowledgeBaseListService names = mock(KnowledgeBaseListService.class);
  private final KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
  private final OwnerScope owner = mock(OwnerScope.class);
  private final ChatClient answerClient = mock(ChatClient.class);
  private final ChatClient reviewClient = mock(ChatClient.class);
  private final ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class, Answers.RETURNS_SELF);
  private final ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
  private final ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
  private final ResolvedScope scope = ResolvedScope.ofSelection(List.of(4L, 23L));
  private KnowledgeBaseQueryService service;

  @BeforeEach
  void setup() throws Exception {
    AiCallContextHolder.set(RagEvidenceSessionTest.session().callContext());
    properties.getReview().setEnabled(true);
    properties.getRewrite().setEnabled(false);
    properties.getTools().setEnabled(false);
    when(owner.effectiveOwnerId()).thenReturn(1L);
    when(repository.findAccessibleIdsByVectorStatus(any(), any())).thenReturn(List.of(4L, 23L));
    when(names.getAccessibleKnowledgeBaseNames(anyList())).thenReturn(Map.of(4L, "数学", 23L, "Spring AI"));
    when(vectors.similaritySearch(anyString(), anyList(), anyInt(), anyDouble())).thenReturn(List.of(
        new Document("tech", "学习 ChatClient 与向量检索", Map.of("kb_id", "23")),
        new Document("math", "扇形圆心角", Map.of("kb_id", "4"))));
    when(registry.getPlainChatClient()).thenReturn(answerClient);
    when(registry.getChatClientOrDefault(eq(""), any(LlmProviderRegistry.ShortTextOptions.class))).thenReturn(reviewClient);
    when(invoker.invokeOnceWithoutSchemaValidation(eq(reviewClient), anyString(), anyString(), any(),
        any(), anyString(), anyString(), any(), any())).thenReturn(new ReviewDecision(List.of("E1")));
    when(answerClient.prompt()).thenReturn(request);
    when(request.call()).thenReturn(call);
    when(request.stream()).thenReturn(stream);
    when(call.content()).thenReturn("学习 ChatClient〔E1〕");
    when(stream.content()).thenReturn(Flux.just("学习 ChatClient〔", "E1", "〕"));
    var metrics = mock(RagMetrics.class);
    var assembler = new RagContextAssembler(mock(VectorRepository.class), names, properties, metrics);
    var resources = new DefaultResourceLoader();
    var reviewer = new RagEvidenceReviewService(registry, invoker, properties, assembler, metrics,
        new ObjectMapper(), resources);
    service = new KnowledgeBaseQueryService(registry, metrics, vectors, names,
        mock(KnowledgeBaseCountService.class), properties, resources, null,
        mock(RagQueryTraceService.class), null, null, owner, repository, assembler, reviewer);
  }

  @AfterEach
  void cleanup() { AiCallContextHolder.clear(); }

  @Test
  @DisplayName("同步答案仅接收复核后原文并按有效引用输出出处，不包含数学")
  void syncUsesOnlyReviewedText() {
    String answer = service.answerQuestion(scope, "Spring AI 学什么");
    assertThat(answer).contains("Spring AI").doesNotContain("数学");
    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(request).user(prompt.capture());
    assertThat(prompt.getValue()).contains("学习 ChatClient", "〔E1〕").doesNotContain("扇形圆心角");
  }

  @Test
  @DisplayName("跨分片引用保留流式输出，结束后只按有效引用列出处")
  void streamUsesCitationsAtEnd() {
    String first = "学习 ChatClient。".repeat(15) + "〔";
    when(stream.content()).thenReturn(Flux.just(first, "E1", "〕"));
    List<RagQueryExecution> trace = new ArrayList<>();
    List<String> chunks = service.answerQuestionStream(scope, "Spring AI 学什么", List.of(), trace::add)
        .collectList().block();
    assertThat(chunks).contains(first, "E1", "〕");
    assertThat(String.join("", chunks)).contains("出处", "Spring AI").doesNotContain("数学");
    assertThat(trace.getFirst().contextText()).contains("ChatClient").doesNotContain("扇形圆心角");
    assertThat(trace.getFirst().directDocumentIds()).containsExactly("tech");
  }

  @Test
  @DisplayName("预检索、工具和只检索入口统一复核，新搜索只用当前明确问题复核")
  void allEntryPointsReview() {
    assertThat(service.searchKnowledge("Spring AI", scope).documents()).extracting(Document::getId)
        .containsExactly("tech");
    assertThat(service.retrieveOnly(scope.knowledgeBaseIds(), "Spring AI", List.of()).contextText())
        .doesNotContain("扇形圆心角");
    assertThat(String.join("", service.answerQuestionStreamWithTools(scope, 14L, "Spring AI", List.of(), null)
        .collectList().block())).doesNotContain("数学");
  }

  @Test
  @DisplayName("缺引用不能退回所有检索结果，关闭出处仍保留复核")
  void missingCitationsAndDisabledSources() {
    when(call.content()).thenReturn("学习 ChatClient");
    assertThat(service.answerQuestion(scope, "Spring AI")).contains("未包含有效资料引用").doesNotContain("**出处**");
  }

  @Test
  @DisplayName("关闭出处仍进行复核，但不强制编号和缺引用提示")
  void disabledSourcesStillReviews() {
    ReflectionTestUtils.setField(service, "citeSources", false);
    when(call.content()).thenReturn("学习 ChatClient");
    assertThat(service.answerQuestion(scope, "Spring AI")).isEqualTo("学习 ChatClient");
    verify(invoker).invokeOnceWithoutSchemaValidation(eq(reviewClient), anyString(), anyString(), any(),
        any(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("事件流换线程仍沿用入口授权事实，每次订阅有独立复核会话")
  void eventStreamCapturesAuthorizationBeforeAsync() {
    var events = service.answerQuestionEventStreamWithTools(scope, 14L, "Spring AI", List.of(), null);
    when(owner.effectiveOwnerId()).thenReturn(null);
    AiCallContextHolder.clear();
    var output = events.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).collectList().block();
    assertThat(output).anySatisfy(event -> assertThat(event.name()).isEqualTo("sources"));
    assertThat(output.stream().map(event -> event.persistenceText()).reduce("", String::concat))
        .contains("Spring AI").doesNotContain("数学");
    verify(repository, org.mockito.Mockito.atLeastOnce()).findAccessibleIdsByVectorStatus(eq(1L), any());
    org.mockito.Mockito.clearInvocations(invoker);
    events.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).collectList().block();
    verify(invoker).invokeOnceWithoutSchemaValidation(eq(reviewClient), anyString(), anyString(), any(),
        any(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("正常空复核不会阻断工具化对话历史回答")
  void emptyEvidenceStillAllowsHistory() {
    when(invoker.invokeOnceWithoutSchemaValidation(eq(reviewClient), anyString(), anyString(), any(),
        any(), anyString(), anyString(), any(), any())).thenReturn(new ReviewDecision(List.of()));
    when(stream.content()).thenReturn(Flux.just("你刚才问了 Spring AI"));
    String answer = String.join("", service.answerQuestionStreamWithTools(scope, 14L,
        "我刚刚问了什么", List.of(), null).collectList().block());
    assertThat(answer).isEqualTo("你刚才问了 Spring AI");
  }

  @Test
  @DisplayName("复核失败同步保留错误码，流式不调用回答模型")
  void reviewFailureDoesNotGenerate() {
    when(invoker.invokeOnceWithoutSchemaValidation(eq(reviewClient), anyString(), anyString(), any(),
        any(), anyString(), anyString(), any(), any())).thenThrow(RagEvidenceSession.failure("timeout"));
    assertThatThrownBy(() -> service.answerQuestion(scope, "Spring AI"))
        .isInstanceOf(BusinessException.class).hasMessageContaining("复核失败");
    assertThat(String.join("", service.answerQuestionStream(scope, "Spring AI", List.of())
        .collectList().block())).contains("复核失败").doesNotContain("出处**");
    org.mockito.Mockito.verifyNoInteractions(answerClient);
  }

  @Test
  @DisplayName("SDK吞工具异常时失败标记仍阻止后续分片和无工具兜底")
  void swallowedToolFailureStopsStream() {
    ReflectionTestUtils.setField(service, "toolsEnabled", true);
    when(registry.getAgentChatClient(null)).thenReturn(answerClient);
    AtomicReference<RagEvidenceSession> captured = new AtomicReference<>();
    org.mockito.Mockito.doAnswer(invocation -> {
      Map<String, Object> context = invocation.getArgument(0);
      captured.set((RagEvidenceSession) context.get(RagToolContextKeys.EVIDENCE_SESSION));
      return request;
    }).when(request).toolContext(any());
    when(stream.content()).thenReturn(Flux.defer(() -> {
      captured.get().markFailed();
      return Flux.just("不应输出的内容");
    }));
    String answer = String.join("", service.answerQuestionStreamWithTools(scope, 14L,
        "Spring AI", List.of(), null).collectList().block());
    assertThat(answer).contains("中断").doesNotContain("不应输出", "**出处**");
    var evidence = RagEvidenceSessionTest.session();
    var query = mock(KnowledgeBaseQueryService.class);
    when(query.searchKnowledge(anyString(), any(), eq(evidence)))
        .thenAnswer(invocation -> { evidence.markFailed(); throw RagEvidenceSession.failure("timeout"); });
    var tool = new RagKnowledgeSearchTool(query);
    var context = new ToolContext(Map.of(RagToolContextKeys.SCOPE, scope,
        RagToolContextKeys.EVIDENCE_SESSION, evidence));
    assertThatThrownBy(() -> tool.searchKnowledgeBase("宽泛检索", context)).isInstanceOf(BusinessException.class);
    assertThat(evidence.failed()).isTrue();
  }

  @Test
  @DisplayName("生产入口缺计费上下文失败关闭，不以系统身份绕过")
  void missingBillingContextFailsClosed() {
    AiCallContextHolder.clear();
    assertThatThrownBy(() -> service.answerQuestion(scope, "Spring AI")).isInstanceOf(BusinessException.class)
        .hasMessageContaining("缺少问答计费上下文");
    org.mockito.Mockito.verifyNoInteractions(answerClient, reviewClient);
  }

  @Test
  @DisplayName("工具不能通过丢弃证据会话重新创建批次绕过上限")
  void toolWithoutEvidenceSessionFailsClosed() {
    var tool = new RagKnowledgeSearchTool(service);
    assertThatThrownBy(() -> tool.searchKnowledgeBase("Spring AI",
        new ToolContext(Map.of(RagToolContextKeys.SCOPE, scope))))
        .isInstanceOf(BusinessException.class).hasMessageContaining("工具缺少证据会话");
    org.mockito.Mockito.verifyNoInteractions(invoker);
  }
}
