package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.LlmProviderRegistry.ShortTextOptions;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import interview.guide.modules.knowledgebase.service.RagContextAssembler.PreparedContext;
import interview.guide.modules.knowledgebase.service.RagContextAssembler.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class RagEvidenceReviewService {
  private final LlmProviderRegistry registry;
  private final StructuredOutputInvoker invoker;
  private final KnowledgeBaseQueryProperties properties;
  private final RagContextAssembler assembler;
  private final RagMetrics metrics;
  private final ObjectMapper mapper;
  private final String systemTemplate;
  private final PromptTemplate userTemplate;

  public record ReviewDecision(List<String> keepIds) { }

  public RagEvidenceReviewService(LlmProviderRegistry registry, StructuredOutputInvoker invoker,
      KnowledgeBaseQueryProperties properties, RagContextAssembler assembler, RagMetrics metrics,
      ObjectMapper mapper, ResourceLoader loader) throws IOException {
    this.registry = registry;
    this.invoker = invoker;
    this.properties = properties;
    this.assembler = assembler;
    this.metrics = metrics;
    this.mapper = mapper;
    this.systemTemplate = loader.getResource("classpath:prompts/knowledgebase-review-system.st")
        .getContentAsString(StandardCharsets.UTF_8);
    this.userTemplate = new PromptTemplate(loader.getResource("classpath:prompts/knowledgebase-review-user.st")
        .getContentAsString(StandardCharsets.UTF_8));
  }

  public PreparedContext review(String question, PreparedContext prepared, RagEvidenceSession session) {
    if (prepared.status() != Status.READY || prepared.units().isEmpty()) return prepared;
    long start = System.nanoTime();
    String[] outcome = {"error"};
    SecurityContext previous = SecurityContextHolder.getContext();
    SecurityContextHolder.setContext(session.securityContext());
    try {
      return AiCallContextHolder.callWith(session.callContext(), () -> {
        var config = properties.getReview();
        var client = registry.getChatClientOrDefault(config.getProvider(),
            new ShortTextOptions(config.getTimeout(), config.getMaxOutputTokens()));
        var converter = new BeanOutputConverter<>(ReviewDecision.class);
        String system = systemTemplate + "\n" + converter.getFormat();
        String user = userTemplate.render(Map.of("question", question, "evidence", reviewInput(prepared)));
        String key = RagEvidenceSession.hash(question + session.authorizationKey() + prepared.text()
            + prepared.blocks().stream().map(block -> block.document().getId() + ":"
                + block.document().getMetadata()).toList()
            + System.identityHashCode(client) + system + config.toString());
        return session.reviewCached(key, config.getMaxBatchesPerRequest(), properties.getContext().getMaxTokens(),
            () -> {
              if (RagContextAssembler.tokenCount(system + user) > config.getMaxInputTokens()) {
                throw RagEvidenceSession.failure("INPUT_LIMIT：复核输入超限");
              }
              Set<String> ids = prepared.units().stream().map(RagContextAssembler.EvidenceUnit::id)
                  .collect(java.util.stream.Collectors.toSet());
              ReviewDecision decision = invoker.invokeOnceWithoutSchemaValidation(client, system, user,
                  converter, ErrorCode.RAG_EVIDENCE_REVIEW_FAILED, "资料相关性复核失败", "rag-review", log,
                  result -> validDecision(result, ids));
              return assembler.retainEvidence(prepared, new LinkedHashSet<>(decision.keepIds()));
            }, result -> outcome[0] = result);
      });
    } catch (Throwable exception) {
      session.markFailed();
      if (exception instanceof BusinessException business) throw business;
      throw new BusinessException(ErrorCode.RAG_EVIDENCE_REVIEW_FAILED, "资料复核服务暂时不可用", exception);
    } finally {
      SecurityContextHolder.setContext(previous);
      metrics.recordStageDuration("review", outcome[0], System.nanoTime() - start);
      log.info("RAG_REVIEW result={} inputBlocks={} inputUnits={} elapsedMs={}", outcome[0],
          prepared.blocks().size(), prepared.units().size(), (System.nanoTime() - start) / 1_000_000);
    }
  }

  static boolean validDecision(ReviewDecision decision, Set<String> validIds) {
    return decision != null && decision.keepIds() != null
        && decision.keepIds().size() <= validIds.size()
        && decision.keepIds().stream().allMatch(id -> id != null && validIds.contains(id))
        && new LinkedHashSet<>(decision.keepIds()).size() == decision.keepIds().size();
  }

  public String reviewInput(PreparedContext prepared) {
    StringBuilder text = new StringBuilder("原文块：\n");
    prepared.blocks().forEach(block -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", block.id());
      row.put("title", block.title());
      row.put("path", block.path());
      row.put("text", block.document().getText());
      text.append(mapper.writeValueAsString(row)).append('\n');
    });
    text.append("证据单元：\n");
    prepared.units().forEach(unit -> text.append(mapper.writeValueAsString(unit)).append('\n'));
    return text.toString();
  }
}
