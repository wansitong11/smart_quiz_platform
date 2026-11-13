package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallMeter;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.config.LlmEmbeddingConfig;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.chat.prompt.PromptTemplate;
import interview.guide.modules.knowledgebase.service.scope.ResolvedScope;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Tag("rag-eval")
@EnabledIfEnvironmentVariable(named = "RUN_RAG_EVAL", matches = "true")
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class RagEvidenceReviewEvaluationTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final DefaultResourceLoader resources = new DefaultResourceLoader();
  private final Path output = Path.of("build/reports/rag-review",
      LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
  private final Map<String, Object> report = new LinkedHashMap<>();
  private final List<Map<String, Object>> runs = new ArrayList<>();
  private final List<Map<String, Object>> calls = new ArrayList<>();
  private record Sample(String id, String split, String question, long kb, List<String> anchors) { }

  @Test
  @DisplayName("冻结真实题集三轮只读测量：补邻复核、流式回答和引用共同验收")
  void verifiesReadOnlyRealCorpus() throws Exception {
    Files.createDirectories(output);
    String dataset = resources.getResource("classpath:rag-review/dataset.json").getContentAsString(StandardCharsets.UTF_8);
    Files.writeString(output.resolve("dataset.json"), dataset);
    report.put("datasetSha256", RagEvidenceSession.hash(dataset));
    report.put("databaseReadOnly", true);
    report.put("cost", "未计价；使用本地计量器，不写消息、轨迹、费用账本");
    report.put("runs", runs);
    report.put("calls", calls);
    List<Sample> samples = mapper.readValue(dataset, new TypeReference<List<Sample>>() { });
    String selected = System.getenv().getOrDefault("RAG_REVIEW_EVAL_SAMPLES", "");
    if (!selected.isBlank()) {
      Set<String> selectedIds = Set.of(selected.split(","));
      samples = samples.stream().filter(sample -> selectedIds.contains(sample.id())).toList();
      assertThat(samples).as("必须选中至少一道已冻结题目").isNotEmpty();
    }
    report.put("selectedSamples", samples.stream().map(Sample::id).toList());
    report.put("protocolVersion", "v2-explicit-citation-allowlist");
    report.put("purpose", "冻结题集已用于 v1，本次是协议回归，不冒充全新留出");
    report.put("citationPromptSha256", RagEvidenceSession.hash(resources.getResource(
        "classpath:prompts/knowledgebase-evidence-citations.st").getContentAsString(StandardCharsets.UTF_8)));
    String url = System.getenv().getOrDefault("RAG_REPLAY_DB_URL", "jdbc:postgresql://"
        + System.getenv().getOrDefault("POSTGRES_HOST", "localhost") + ":"
        + System.getenv().getOrDefault("POSTGRES_PORT", "5432") + "/"
        + System.getenv().getOrDefault("POSTGRES_DB", "smart-quiz"));
    try (var dataSource = new SingleConnectionDataSource(url,
        Objects.requireNonNull(System.getenv("POSTGRES_USER")),
        Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD")), true)) {
      dataSource.getConnection().setReadOnly(true);
      dataSource.getConnection().setAutoCommit(false);
      var jdbc = new JdbcTemplate(dataSource);
      var registry = registry(jdbc);
      report.put("provider", registry.getDefaultChatProviderId());
      report.put("model", registry.getDefaultChatModelName());
      Map<Long, String> nameById = new LinkedHashMap<>();
      jdbc.query("SELECT id,name FROM knowledge_bases WHERE vector_status='COMPLETED' "
          + "AND (owner_id=1 OR visibility='SHARED') ORDER BY id", row -> {
            nameById.put(row.getLong(1), row.getString(2));
          });
      List<Long> allowed = List.copyOf(nameById.keySet());
      var stored = jdbc.query("SELECT v.id,v.content,v.metadata::text FROM vector_store v "
          + "JOIN knowledge_bases k ON v.metadata->>'kb_id'=k.id::text "
          + "WHERE k.vector_status='COMPLETED' AND (k.owner_id=1 OR k.visibility='SHARED')",
          (row, index) -> new Document(row.getString(1), row.getString(2),
              mapper.readValue(row.getString(3), new TypeReference<Map<String, Object>>() { })));
      report.put("corpus", stored);
      List<String> missingFixtures = new ArrayList<>();
      for (Sample sample : samples) {
        for (String anchor : sample.anchors()) {
          if (stored.stream().noneMatch(document -> kbId(document) == sample.kb()
              && document.getText().contains(anchor))) {
            missingFixtures.add(sample.id());
            runs.add(Map.of("sample", sample, "outcome", "FIXTURE_MISSING", "missingAnchor", anchor));
          }
        }
      }
      report.put("missingFixtures", missingFixtures);
      Files.writeString(output.resolve("corpus-frozen.json"), mapper.writeValueAsString(stored));
      report.put("corpusSha256", RagEvidenceSession.hash(mapper.writeValueAsString(stored)));
      save();
      var names = mock(KnowledgeBaseListService.class);
      when(names.getAccessibleKnowledgeBaseNames(anyList())).thenReturn(nameById);
      var repository = mock(KnowledgeBaseRepository.class);
      when(repository.findAccessibleIdsByVectorStatus(any(), any())).thenReturn(allowed);
      var owner = mock(OwnerScope.class);
      when(owner.effectiveOwnerId()).thenReturn(1L);
      var vectorRepository = new VectorRepository(jdbc);
      EmbeddingModel embedding = new LlmEmbeddingConfig().embeddingModel(registry);
      var store = PgVectorStore.builder(jdbc, embedding).dimensions(1024)
          .distanceType(PgDistanceType.COSINE_DISTANCE).initializeSchema(false).build();
      var vectors = new KnowledgeBaseVectorService(store, vectorRepository);
      var properties = new KnowledgeBaseQueryProperties();
      properties.setSectionNeighborEnabled(true);
      properties.getReview().setEnabled(true);
      var metrics = mock(RagMetrics.class);
      var assembler = new RagContextAssembler(vectorRepository, names, properties, metrics);
      var invoker = new StructuredOutputInvoker(new StructuredOutputProperties(), null, null);
      var reviewer = spy(new RagEvidenceReviewService(registry, invoker, properties, assembler,
          metrics, mapper, resources));
      AtomicCapture capture = new AtomicCapture();
      doAnswer(invocation -> {
        capture.prepared = invocation.getArgument(1);
        capture.reviewed = (RagContextAssembler.PreparedContext) invocation.callRealMethod();
        capture.session = invocation.getArgument(2);
        return capture.reviewed;
      }).when(reviewer).review(anyString(), any(), any());
      var service = new KnowledgeBaseQueryService(registry, metrics, vectors, names,
          mock(KnowledgeBaseCountService.class), properties, resources, null, null,
          null, null, owner, repository, assembler, reviewer);
      boolean allPassed = true;
      for (Sample sample : samples) {
        if (missingFixtures.contains(sample.id())) continue;
        for (int repetition = 1; repetition <= 3; repetition++) {
          Map<String, Object> run = new LinkedHashMap<>();
          runs.add(run);
          run.put("sample", sample);
          run.put("repetition", repetition);
          List<RagQueryExecution> traces = new ArrayList<>();
          long start = System.nanoTime();
          long[] first = {0};
          AiCallContextHolder.set(RagEvidenceSessionTest.session().callContext());
          try {
            List<String> chunks = service.answerQuestionStream(allowed, sample.question(), List.of(), traces::add)
                .doOnNext(chunk -> { if (first[0] == 0) first[0] = System.nanoTime(); }).collectList().block();
            String answer = String.join("", Objects.requireNonNull(chunks));
            run.put("answer", answer);
            run.put("chunks", chunks.size());
            run.put("firstChunkMs", (first[0] - start) / 1_000_000);
            run.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
            run.put("prepared", capture.prepared);
            run.put("reviewed", capture.reviewed);
            run.put("trace", traces);
            var citations = capture.session == null ? null : capture.session.citations(answer);
            run.put("citations", citations);
            boolean passed = capture.reviewed != null && !capture.session.failed()
                && capture.reviewed.blocks().stream().allMatch(block -> block.kbId() == sample.kb())
                && !capture.reviewed.units().isEmpty()
                && sample.anchors().stream().allMatch(anchor -> capture.reviewed.text().contains(anchor))
                && citations != null && !citations.sources().isEmpty()
                && citations.sources().stream().allMatch(source -> source.kbId() == sample.kb())
                && !answer.contains("【错误】") && !answer.contains("部分引用编号无效");
            run.put("passed", passed);
            allPassed &= passed;
          } finally {
            AiCallContextHolder.clear();
            capture.prepared = null;
            capture.reviewed = null;
            capture.session = null;
            save();
          }
        }
      }
      report.put("passed", allPassed);
      report.put("activationGatePassed", false);
      dataSource.getConnection().rollback();
      assertThat(allPassed).as("真实质量门禁未通过，保留报告，不打开运行开关").isTrue();
      assertThat(missingFixtures).as("存在 FIXTURE_MISSING：该题不下结论，运行开关保持关闭").isEmpty();
    } finally {
      save();
    }
  }

  private static class AtomicCapture {
    RagContextAssembler.PreparedContext prepared;
    RagContextAssembler.PreparedContext reviewed;
    RagEvidenceSession session;
  }

  @Test
  @DisplayName("独立内存退款双来源与真实资料无答案历史问题不串用出处")
  void verifiesSpecialCasesReadOnly() throws Exception {
    Files.createDirectories(output);
    report.put("runs", runs);
    report.put("calls", calls);
    report.put("databaseReadOnly", true);
    report.put("cost", "未计价；本地计量，无账本写入");
    String url = System.getenv().getOrDefault("RAG_REPLAY_DB_URL", "jdbc:postgresql://"
        + System.getenv().getOrDefault("POSTGRES_HOST", "localhost") + ":"
        + System.getenv().getOrDefault("POSTGRES_PORT", "5432") + "/"
        + System.getenv().getOrDefault("POSTGRES_DB", "smart-quiz"));
    try (var dataSource = new SingleConnectionDataSource(url,
        Objects.requireNonNull(System.getenv("POSTGRES_USER")),
        Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD")), true)) {
      dataSource.getConnection().setReadOnly(true);
      dataSource.getConnection().setAutoCommit(false);
      var jdbc = new JdbcTemplate(dataSource);
      var registry = registry(jdbc);
      EmbeddingModel embedding = new LlmEmbeddingConfig().embeddingModel(registry);
      var names = mock(KnowledgeBaseListService.class);
      when(names.getAccessibleKnowledgeBaseNames(anyList())).thenReturn(Map.of());
      var properties = new KnowledgeBaseQueryProperties();
      properties.getReview().setEnabled(true);
      properties.setSectionNeighborEnabled(true);
      var metrics = mock(RagMetrics.class);
      var assembler = new RagContextAssembler(new VectorRepository(jdbc), names, properties, metrics);
      var reviewer = new RagEvidenceReviewService(registry,
          new StructuredOutputInvoker(new StructuredOutputProperties(), null, null), properties,
          assembler, metrics, mapper, resources);
      var documents = jdbc.query("SELECT v.id,v.content,v.metadata::text FROM vector_store v "
          + "JOIN knowledge_bases k ON v.metadata->>'kb_id'=k.id::text "
          + "WHERE k.vector_status='COMPLETED' AND (k.owner_id=1 OR k.visibility='SHARED')",
          (row, index) -> new Document(row.getString(1), row.getString(2), mapper.readValue(row.getString(3),
              new TypeReference<Map<String, Object>>() { })));
      List<Long> allowed = documents.stream().map(this::kbId).distinct().toList();
      boolean passed = specialCases(registry, embedding, reviewer, assembler, documents, allowed);
      report.put("passed", passed);
      dataSource.getConnection().rollback();
      assertThat(passed).isTrue();
    } finally { save(); }
  }

  private boolean specialCases(LlmProviderRegistry registry, EmbeddingModel embedding,
      RagEvidenceReviewService reviewer, RagContextAssembler assembler, List<Document> realDocuments,
      List<Long> allowed) throws Exception {
    var store = SimpleVectorStore.builder(embedding).build();
    List<Long> ids = List.of(9001L, 9002L, 9003L);
    AiCallContextHolder.set(RagEvidenceSessionTest.session().callContext());
    try {
      store.add(List.of(new Document("refund-a", "退款成功后，款项原路返回，三个工作日内到账。",
              Map.of("kb_id", "9001", "document_title", "退款时效")),
          new Document("refund-b", "退款金额超过500元，必须由主管审批。",
              Map.of("kb_id", "9002", "document_title", "退款审批")),
          new Document("unrelated-c", "学习扇形统计图，先计算百分数和圆心角。",
              Map.of("kb_id", "9003", "document_title", "干扰资料"))));
      boolean passed = true;
      for (int repetition = 1; repetition <= 3; repetition++) {
        String question = "退款后多久到账，什么条件下需要主管审批？";
        var documents = store.similaritySearch(SearchRequest.builder().query(question).topK(3)
            .similarityThreshold(0).build());
        var prepared = assembler.prepareEvidence(documents, new LinkedHashSet<>(ids), 20, true);
        var session = new RagEvidenceSession(1L, 1L, false, ResolvedScope.ofSelection(ids),
            AiCallContextHolder.get(), question);
        var reviewed = reviewer.review(question, prepared, session);
        String system = resources.getResource("classpath:prompts/knowledgebase-query-system.st")
            .getContentAsString(StandardCharsets.UTF_8) + "\n" + resources.getResource(
                "classpath:prompts/knowledgebase-evidence-citations.st").getContentAsString(StandardCharsets.UTF_8);
        var user = new PromptTemplate(resources.getResource("classpath:prompts/knowledgebase-query-user.st")
            .getContentAsString(StandardCharsets.UTF_8)).render(Map.of("question", question, "context", reviewed.text()));
        String answer = registry.getPlainChatClient().prompt().system(system).user(user).stream().content()
            .reduce("", String::concat).block();
        var citations = session.citations(Objects.requireNonNull(answer));
        boolean accepted = reviewed.blocks().stream().map(RagContextAssembler.EvidenceBlock::kbId)
            .collect(java.util.stream.Collectors.toSet()).equals(Set.of(9001L, 9002L))
            && citations.sources().size() == 2 && citations.warning().isEmpty();
        runs.add(Map.of("case", "synthetic-two-sources", "repetition", repetition, "prepared", prepared,
            "reviewed", reviewed, "answer", answer, "citations", citations, "passed", accepted));
        passed &= accepted;
        for (String unsupported : List.of("我家的 WiFi 密码是多少？", "我刚刚问了什么？")) {
          var noAnswerSession = new RagEvidenceSession(1L, 1L, false,
              ResolvedScope.ofSelection(allowed), AiCallContextHolder.get(), unsupported);
          var noAnswerPrepared = assembler.prepareEvidence(realDocuments,
              new LinkedHashSet<>(allowed), 20, true);
          var empty = reviewer.review(unsupported, noAnswerPrepared, noAnswerSession);
          boolean emptyPassed = empty.units().isEmpty() && !noAnswerSession.failed();
          runs.add(Map.of("case", "unsupported-or-history", "question", unsupported,
              "repetition", repetition, "prepared", noAnswerPrepared, "reviewed", empty, "passed", emptyPassed));
          passed &= emptyPassed;
        }
        save();
      }
      return passed;
    } finally { AiCallContextHolder.clear(); }
  }

  private long kbId(Document document) { return Long.parseLong(document.getMetadata().get("kb_id").toString()); }

  private LlmProviderRegistry registry(JdbcTemplate jdbc) {
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(resources.getResource("classpath:application.yml"));
    var environment = new StandardEnvironment();
    environment.getPropertySources().addLast(new PropertiesPropertySource("application", Objects.requireNonNull(yaml.getObject())));
    var properties = Binder.get(environment).bind("app.ai", Bindable.of(LlmProviderProperties.class)).get();
    var encryption = new ApiKeyEncryptionService(properties);
    ReflectionTestUtils.invokeMethod(encryption, "init");
    var providers = mock(LlmProviderRepository.class);
    List<LlmProviderEntity> entities = jdbc.query("SELECT * FROM llm_provider_config WHERE enabled=true",
        new BeanPropertyRowMapper<>(LlmProviderEntity.class));
    when(providers.findById(any())).thenAnswer(invocation -> entities.stream()
        .filter(entity -> entity.getId().equals(invocation.getArgument(0))).findFirst());
    var globals = mock(LlmGlobalSettingRepository.class);
    var settings = jdbc.query("SELECT * FROM llm_global_setting WHERE id=1", new BeanPropertyRowMapper<>(LlmGlobalSettingEntity.class));
    when(globals.findById(1L)).thenReturn(settings.stream().findFirst());
    AiCallMeter meter = new AiCallMeter() {
      @Override
      public Advisor chatAdvisor(String providerId, String model, String modality) {
        return new CaptureAdvisor(providerId, model);
      }
      @Override
      public EmbeddingModel wrapEmbedding(EmbeddingModel delegate, String providerId, String model) { return delegate; }
    };
    return new LlmProviderRegistry(properties, providers, globals, null, encryption, null, null, null, null, meter);
  }

  private final class CaptureAdvisor implements CallAdvisor, StreamAdvisor {
    private final String provider;
    private final String model;
    CaptureAdvisor(String provider, String model) { this.provider = provider; this.model = model; }
    @Override public String getName() { return "rag-review-readonly-meter"; }
    @Override public int getOrder() { return 0; }
    private Map<String, Object> begin(ChatClientRequest request) {
      Map<String, Object> call = new LinkedHashMap<>();
      calls.add(call);
      call.put("provider", provider);
      call.put("model", model);
      call.put("prompt", request.prompt().toString());
      return call;
    }
    private void usage(Map<String, Object> call, ChatClientResponse response) {
      if (response.chatResponse() != null) call.put("usage", response.chatResponse().getMetadata().getUsage());
    }
    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
      var call = begin(request);
      long start = System.nanoTime();
      try {
        var response = chain.nextCall(request);
        usage(call, response);
        return response;
      } finally { call.put("elapsedMs", (System.nanoTime() - start) / 1_000_000); }
    }
    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
      var call = begin(request);
      long start = System.nanoTime();
      return chain.nextStream(request).doOnNext(response -> usage(call, response))
          .doFinally(signal -> call.put("elapsedMs", (System.nanoTime() - start) / 1_000_000));
    }
  }

  private void save() throws Exception {
    Files.writeString(output.resolve("evaluation.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
  }
}
