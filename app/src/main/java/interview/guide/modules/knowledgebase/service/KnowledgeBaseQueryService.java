package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.model.RagQueryTraceEntity;
import interview.guide.modules.knowledgebase.model.RagScopeKind;
import interview.guide.modules.knowledgebase.service.scope.ResolvedScope;
import interview.guide.modules.knowledgebase.service.tool.ConversationHistoryReader;
import interview.guide.modules.knowledgebase.service.tool.RagConversationHistoryTool;
import interview.guide.modules.knowledgebase.service.tool.RagKnowledgeSearchTool;
import interview.guide.modules.knowledgebase.service.tool.RagToolContextKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Lazy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    private final LlmProviderRegistry llmProviderRegistry;
    private final RagMetrics ragMetrics;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final boolean mergeOriginalQuery;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;
    private final int candidateMultiplier;
    private final int perKbQuota;
    private final boolean citeSources;
    private final PromptTemplate agentSystemPromptTemplate;
    private final PromptTemplate agentUserPromptTemplate;
    private final boolean toolsEnabled;
    private final ConversationHistoryReader historyReader;
    private final RagQueryTraceService traceService;
    private final RagKnowledgeSearchTool knowledgeSearchTool;
    private final RagConversationHistoryTool conversationHistoryTool;

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            RagMetrics ragMetrics,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader,
            ConversationHistoryReader historyReader,
            RagQueryTraceService traceService,
            @Lazy RagKnowledgeSearchTool knowledgeSearchTool,
            @Lazy RagConversationHistoryTool conversationHistoryTool) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.ragMetrics = ragMetrics;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.mergeOriginalQuery = queryProperties.getSearch().isMergeOriginalQuery();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
        this.candidateMultiplier = Math.max(1, queryProperties.getSearch().getCandidateMultiplier());
        this.perKbQuota = Math.max(1, queryProperties.getSearch().getPerKbQuota());
        this.citeSources = queryProperties.getSearch().isCiteSources();
        this.historyReader = historyReader;
        this.traceService = traceService;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.conversationHistoryTool = conversationHistoryTool;
        this.toolsEnabled = queryProperties.getTools().isEnabled();
        this.agentSystemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getTools().getAgentSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.agentUserPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getTools().getAgentUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getPlainChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        return answerQuestion(ResolvedScope.ofSelection(knowledgeBaseIds), question);
    }

    /**
     * 基于解析后的检索范围回答用户问题（RAG）。
     * <p>
     * 范围里的资料列表为空表示「全部资料」（由 {@code KnowledgeBaseVectorService} 的
     * 既有语义承载），因此跨库检索不需要新的检索接口。
     * </p>
     */
    public String answerQuestion(ResolvedScope scope, String question) {
        long totalStart = System.nanoTime();
        String normalized = normalizeQuestion(question);
        log.info("收到知识库提问: scope={}, kbIds={}, questionLength={}",
            scope.kind(), scope.knowledgeBaseIds(), normalized.length());
        if (normalized.isBlank()) {
            ragMetrics.recordRequest("sync", "reject");
            ragMetrics.recordRefusal("invalid_request");
            ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart);
            return NO_RESULT_RESPONSE;
        }
        if (scope.isEmptySelection()) {
            ragMetrics.recordRequest("sync", "reject");
            ragMetrics.recordRefusal("invalid_request");
            ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart);
            return emptyScopeResponse(scope);
        }

        QueryContext queryContext = null;
        long retrievalNanos = 0;
        try {
            queryContext = buildQueryContext(question, List.of());
            long retrievalStart = System.nanoTime();
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, scope);
            retrievalNanos = System.nanoTime() - retrievalStart;

            if (!hasEffectiveHit(relevantDocs)) {
                ragMetrics.recordRequest("sync", "reject");
                ragMetrics.recordRefusal("no_hit");
                ragMetrics.recordStageDuration("rewrite", "reject",
                    queryContext.rewriteDurationMs() * 1_000_000L);
                ragMetrics.recordStageDuration("retrieve", "reject", retrievalNanos);
                ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart);
                return withUnavailableHint(NO_RESULT_RESPONSE, scope);
            }

            // 提问计数按「实际命中的资料」归位，而不是按会话范围全量 +1
            countService.updateQuestionCounts(hitKnowledgeBaseIds(relevantDocs));

            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            long generationStart = System.nanoTime();
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);
            long generationNanos = System.nanoTime() - generationStart;

            boolean rejected = NO_RESULT_RESPONSE.equals(answer);
            ragMetrics.recordRequest("sync", rejected ? "reject" : "success");
            if (rejected) {
                ragMetrics.recordRefusal("no_hit");
            }
            ragMetrics.recordStageDuration("rewrite", rejected ? "reject" : "success",
                queryContext.rewriteDurationMs() * 1_000_000L);
            ragMetrics.recordStageDuration("retrieve", rejected ? "reject" : "success", retrievalNanos);
            ragMetrics.recordStageDuration("generate", rejected ? "reject" : "success", generationNanos);
            ragMetrics.recordStageDuration("total", rejected ? "reject" : "success",
                System.nanoTime() - totalStart);

            log.info("知识库问答完成: scope={}, 命中资料={}",
                scope.kind(), hitKnowledgeBaseIds(relevantDocs));
            return rejected ? answer : withSources(answer, relevantDocs);

        } catch (Exception e) {
            log.error("知识库问答失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            ragMetrics.recordRequest("sync", "error");
            if (queryContext != null) {
                ragMetrics.recordStageDuration("rewrite", "error",
                    queryContext.rewriteDurationMs() * 1_000_000L);
                ragMetrics.recordStageDuration("retrieve", "error", retrievalNanos);
            }
            ragMetrics.recordStageDuration("total", "error", System.nanoTime() - totalStart);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "知识库查询失败，请稍后重试");
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        List<Long> knowledgeBaseIds = request.knowledgeBaseIds() != null
            ? request.knowledgeBaseIds() : List.of();
        String answer = answerQuestion(knowledgeBaseIds, request.question());

        // 获取知识库名称（多个知识库用逗号分隔）；为空表示检索全部资料
        List<String> kbNames = knowledgeBaseIds.isEmpty()
            ? List.of("全部资料")
            : listService.getKnowledgeBaseNames(knowledgeBaseIds);
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）；全部资料时为 null
        Long primaryKbId = knowledgeBaseIds.isEmpty() ? null : knowledgeBaseIds.getFirst();

        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    /**
     * 工具入口：按给定查询直接检索（不做改写），返回片段、命中资料与给模型看的文本。
     * <p>
     * 与主链路共用同一套召回配额与计数归位逻辑，模型通过工具得到的片段
     * 与「预检索」拿到的完全一致。
     * </p>
     */
    public KnowledgeSearchResult searchKnowledge(String query, ResolvedScope scope) {
        String normalized = normalizeQuestion(query);
        if (normalized.isBlank() || scope == null || scope.isEmptySelection()) {
            return KnowledgeSearchResult.empty();
        }
        QueryContext queryContext = new QueryContext(normalized, List.of(normalized),
            resolveSearchParams(normalized), 0);
        List<Document> docs = retrieveRelevantDocs(queryContext, scope);
        if (!hasEffectiveHit(docs)) {
            return KnowledgeSearchResult.empty();
        }

        List<Long> kbIds = hitKnowledgeBaseIds(docs);
        countService.updateQuestionCounts(kbIds);
        Double topScore = docs.stream()
            .map(this::extractScore)
            .filter(java.util.Objects::nonNull)
            .max(Double::compareTo)
            .orElse(null);
        return new KnowledgeSearchResult(docs, kbIds, renderHitsForModel(docs), true, topScore);
    }

    /**
     * 检索结果：给模型看的文本 + 结构化元信息（出处、命中资料、最高分）。
     */
    public record KnowledgeSearchResult(
        List<Document> documents,
        List<Long> knowledgeBaseIds,
        String text,
        boolean hit,
        Double topScore
    ) {
        static KnowledgeSearchResult empty() {
            return new KnowledgeSearchResult(List.of(), List.of(), "", false, null);
        }
    }

    /**
     * 工具化问答（function calling）：规则前置分流 + 预检索兜底 + 模型按工具补充。
     * <p>
     * 与 {@link #answerQuestionStream(ResolvedScope, String, List)} 的区别：
     * <ul>
     *   <li>元问题（问对话历史）走规则通道，不检索，直接注入对话记录——解决
     *       「检索为空 → 直接拒答」导致模型没机会回答的问题</li>
     *   <li>挂载 {@code search_knowledge_base} 与 {@code get_conversation_history} 两个工具，
     *       模型可以换关键词再查、或去读对话记录</li>
     *   <li>预检索结果照旧注入（保证资料类问题质量不退化），并落一条运营轨迹</li>
     * </ul>
     * </p>
     *
     * @param llmProvider 模型 provider（用于轨迹记录与选择客户端）
     */
    public Flux<String> answerQuestionStreamWithTools(ResolvedScope scope, Long sessionId,
                                                     String question, List<Message> history,
                                                     String llmProvider) {
        long totalStart = System.nanoTime();
        String normalized = normalizeQuestion(question);
        RagQueryTraceCollector collector = new RagQueryTraceCollector(sessionId, normalized,
            scope != null ? scope.description() : null, llmProvider);

        if (normalized.isBlank()) {
            settle(collector, true);
            return Flux.just(NO_RESULT_RESPONSE);
        }
        if (scope == null || scope.isEmptySelection()) {
            settle(collector, true);
            return Flux.just(scope == null
                ? "当前会话还没有选择资料，请选择资料或切换为「全部资料」。"
                : emptyScopeResponse(scope));
        }

        List<Document> relevantDocs = List.of();
        String context = "";
        try {
            List<Message> effectiveHistory = sanitizeHistory(history);

            // 统一走「改写 → 预检索」：资料类问题靠它保底，对话类问题由模型按工具自己取
            QueryContext queryContext = buildQueryContext(normalized, effectiveHistory);
            List<String> attemptedQueries = new ArrayList<>();
            relevantDocs = retrieveRelevantDocs(queryContext, scope, attemptedQueries);
            collector.markRewrittenQuery(queryContext.candidateQueries().isEmpty()
                ? normalized : queryContext.candidateQueries().getFirst());
            List<Long> preHitKbIds = hitKnowledgeBaseIds(relevantDocs);
            collector.addRetrievedKbIds(preHitKbIds);
            collector.updateTopScore(topScoreOf(relevantDocs));
            collector.markIntent(RagQueryTraceEntity.INTENT_PRERETRIEVAL);
            // 提问计数按实际命中的资料归位（工具再命中时会额外计数）
            if (!preHitKbIds.isEmpty()) {
                countService.updateQuestionCounts(preHitKbIds);
            }
            context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

            String systemPrompt = agentSystemPromptTemplate.render(Map.of());
            Map<String, Object> variables = new HashMap<>();
            variables.put("context", context);
            variables.put("question", normalized);
            String userPrompt = agentUserPromptTemplate.render(variables);

            ChatClient chatClient = toolsEnabled
                ? llmProviderRegistry.getAgentChatClient(llmProvider)
                : getChatClient();
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt);
            // 对话上下文由 MessageChatMemoryAdvisor 统一注入（ChatMemory），不再手工塞 messages
            if (sessionId != null) {
                spec = spec.advisors(advisors -> advisors.param(
                    ChatMemory.CONVERSATION_ID, String.valueOf(sessionId)));
            }
            if (toolsEnabled) {
                Map<String, Object> toolContext = new HashMap<>();
                toolContext.put(RagToolContextKeys.SESSION_ID, sessionId == null ? 0L : sessionId);
                toolContext.put(RagToolContextKeys.SCOPE, scope);
                toolContext.put(RagToolContextKeys.COLLECTOR, collector);
                spec = spec.tools(knowledgeSearchTool, conversationHistoryTool)
                    .toolContext(toolContext);
            }

            Flux<String> responseFlux = spec.stream().content();
            log.info("开始工具化问答: scope={}, sessionId={}, toolsEnabled={}, 预检索片段={}",
                scope.kind(), sessionId, toolsEnabled, relevantDocs.size());

            long generationStart = System.nanoTime();
            StringBuilder collectedAnswer = new StringBuilder();
            AtomicBoolean hardFailed = new AtomicBoolean(false);
            // lambda 需要 effectively final：把预检索结果固定下来
            List<Document> preRetrievedDocs = relevantDocs;
            // 降级用的普通提示词：保留「对话类问题依据对话记录回答」的例外，但不含工具契约
            String fallbackSystemPrompt = systemPromptTemplate.render(Map.of());

            Flux<String> answerStream = normalizeStreamOutput(responseFlux)
                .doOnNext(collectedAnswer::append)
                .doOnComplete(() -> {
                    boolean rejected = collectedAnswer.isEmpty()
                        || NO_RESULT_RESPONSE.equals(collectedAnswer.toString());
                    settle(collector, rejected);
                    String result = rejected ? "reject" : "success";
                    ragMetrics.recordRequest("stream", result);
                    if (rejected) {
                        ragMetrics.recordRefusal("no_hit");
                    }
                    ragMetrics.recordStageDuration("generate", result, System.nanoTime() - generationStart);
                    ragMetrics.recordStageDuration("total", result, System.nanoTime() - totalStart);
                    log.info("工具化问答完成: sessionId={}, outcome={}, 工具调用={}",
                        sessionId, collector.outcome(), collector.toolCalls().size());
                })
                .onErrorResume(e -> {
                    // 部分 OpenAI 兼容厂商（如 DashScope）的流式工具调用分片会让 Spring AI 的
                    // ChunkMerger 抛错——此时降级为「不带工具的流式生成」，上下文里已经有
                    // 预检索片段与对话记录，用户体验与旧链路一致。
                    log.warn("工具化问答流式失败，降级为无工具生成: sessionId={}, error={}", sessionId,
                        ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
                    return streamWithoutTools(fallbackSystemPrompt, userPrompt, sessionId,
                        collector, totalStart)
                        .onErrorResume(retryError -> {
                            hardFailed.set(true);
                            log.error("无工具降级生成同样失败: sessionId={}, error={}", sessionId,
                                ErrorLogSanitizer.summarize(retryError),
                                ErrorLogSanitizer.forLogging(retryError));
                            // 这是服务不可用，不是「资料里查不到」，轨迹要区分开
                            settle(collector, true, RagQueryTraceEntity.OUTCOME_TOOL_ERROR);
                            ragMetrics.recordRequest("stream", "error");
                            ragMetrics.recordStageDuration("total", "error", System.nanoTime() - totalStart);
                            return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                        });
                });

            // 出处必须等回答结束再算：工具是在流式过程中被调用的，
            // 只有在那一刻才知道模型额外检索到了哪些资料。
            return answerStream.concatWith(Flux.defer(() -> {
                if (hardFailed.get()) {
                    return Flux.empty();
                }
                // 只用了「查对话记录」的工具时，预检索片段与本次回答无关，不标出处
                boolean onlyHistoryTool = !collector.toolCalls().isEmpty()
                    && collector.toolCalls().stream()
                        .allMatch(call -> "get_conversation_history".equals(call.name()));
                if (onlyHistoryTool) {
                    return Flux.empty();
                }
                String block = sourcesBlock(preRetrievedDocs);
                if (block.isBlank()) {
                    block = sourcesBlockFromKbIds(collector.retrievedKbIds());
                }
                return block.isBlank() ? Flux.empty() : Flux.just(block);
            }));
        } catch (Exception e) {
            log.error("工具化问答启动失败: sessionId={}, error={}", sessionId,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            settle(collector, true);
            ragMetrics.recordRequest("stream", "error");
            return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
        }
    }

    /**
     * 结算一次轨迹：定意图、定结果、异步落库。
     * <p>
     * 意图取「模型是否真的用了工具」——用了记 TOOL，没用就是预检索通道；
     * 结果里工具异常优先于无命中，便于区分「查不到」和「工具坏了」。
     * </p>
     */
    private void settle(RagQueryTraceCollector collector, boolean rejected) {
        settle(collector, rejected, null);
    }

    /**
     * @param forcedOutcome 非空时强制使用该结果（用于区分「查不到」与「服务错误」）
     */
    private void settle(RagQueryTraceCollector collector, boolean rejected, String forcedOutcome) {
        if (!collector.toolCalls().isEmpty() && !collector.toolError()) {
            collector.markIntent(RagQueryTraceEntity.INTENT_TOOL);
        }
        if (forcedOutcome != null) {
            collector.markOutcome(forcedOutcome);
        } else if (!rejected) {
            collector.markOutcome(RagQueryTraceEntity.OUTCOME_ANSWERED);
        } else if (collector.toolError()) {
            collector.markOutcome(RagQueryTraceEntity.OUTCOME_TOOL_ERROR);
        } else {
            collector.markOutcome(RagQueryTraceEntity.OUTCOME_NO_HIT);
        }
        traceService.recordAsync(collector);
    }

    /**
     * 不带工具的降级生成：上下文（资料片段 + 对话记录）已经在提示词里，
     * 只是把「工具契约」换成旧版严格提示词，保证厂商不支持流式工具调用时体验不退化。
     */
    private Flux<String> streamWithoutTools(String systemPrompt, String userPrompt,
                                            Long sessionId,
                                            RagQueryTraceCollector collector, long totalStart) {
        collector.markIntent(RagQueryTraceEntity.INTENT_FALLBACK);

        // 仍用 agent 客户端（保留 ChatMemory 注入），只是这一轮不挂工具
        ChatClient.ChatClientRequestSpec spec = llmProviderRegistry
            .getAgentChatClient(null).prompt()
            .system(systemPrompt)
            .user(userPrompt);
        if (sessionId != null) {
            spec = spec.advisors(advisors -> advisors.param(
                ChatMemory.CONVERSATION_ID, String.valueOf(sessionId)));
        }

        StringBuilder collected = new StringBuilder();
        long generationStart = System.nanoTime();
        return normalizeStreamOutput(spec.stream().content())
            .doOnNext(collected::append)
            .doOnComplete(() -> {
                boolean rejected = collected.isEmpty()
                    || NO_RESULT_RESPONSE.equals(collected.toString());
                settle(collector, rejected);
                String result = rejected ? "reject" : "success";
                ragMetrics.recordRequest("stream", result);
                if (rejected) {
                    ragMetrics.recordRefusal("no_hit");
                }
                ragMetrics.recordStageDuration("generate", result, System.nanoTime() - generationStart);
                ragMetrics.recordStageDuration("total", result, System.nanoTime() - totalStart);
                log.info("无工具降级生成完成: sessionId={}, outcome={}, 长度={}",
                    sessionId, collector.outcome(), collected.length());
            });
    }

    /**
     * 片段最高相似度（用于轨迹里的检索质量观测）。
     */
    private Double topScoreOf(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return null;
        }
        return docs.stream()
            .map(this::extractScore)
            .filter(java.util.Objects::nonNull)
            .max(Double::compareTo)
            .orElse(null);
    }

    /**
     * 片段 → 模型可读文本（带资料名，方便模型在回答里正确归属）。
     */
    private String renderHitsForModel(List<Document> docs) {
        Map<Long, String> names = knowledgeBaseNames(hitKnowledgeBaseIds(docs));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            Long kbId = knowledgeBaseIdOf(doc);
            String name = kbId != null ? names.getOrDefault(kbId, "未知资料") : "未知资料";
            sb.append('[').append(i + 1).append("]《").append(name).append("》");
            sb.append(doc.getText() != null ? doc.getText().trim() : "").append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        return answerQuestionStream(ResolvedScope.ofSelection(knowledgeBaseIds), question, history, null);
    }

    /**
     * 流式查询知识库（SSE，按解析后的检索范围），支持多轮上下文。
     */
    public Flux<String> answerQuestionStream(ResolvedScope scope, String question, List<Message> history) {
        return answerQuestionStream(scope, question, history, null);
    }

    /**
     * 按资料 ID 列表流式查询（老入口 / 测评使用），空列表表示全部资料。
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question,
                                             List<Message> history,
                                             java.util.function.Consumer<RagQueryExecution> trace) {
        return answerQuestionStream(ResolvedScope.ofSelection(knowledgeBaseIds), question, history, trace);
    }

    /**
     * 流式查询知识库（SSE，按解析后的检索范围），可选地输出完整执行轨迹。
     * 轨迹收集器仅供测评与可观测性使用，不影响业务行为。
     *
     * @param trace 执行轨迹收集器（可为 null，为 null 时行为与无收集器入口完全一致）
     */
    public Flux<String> answerQuestionStream(ResolvedScope scope, String question, List<Message> history,
                                             java.util.function.Consumer<RagQueryExecution> trace) {
        long totalStart = System.nanoTime();
        String normalized = normalizeQuestion(question);
        log.info("收到知识库流式提问: scope={}, kbIds={}, questionLength={}, historySize={}",
                scope.kind(), scope.knowledgeBaseIds(), normalized.length(),
                history != null ? history.size() : 0);
        // 一次请求只允许记录一次结果：error 路径（含 onErrorResume 吞异常后的 Flux.just）先落，complete 检查后跳过
        java.util.concurrent.atomic.AtomicBoolean resultRecorded = new java.util.concurrent.atomic.AtomicBoolean(false);
        if (normalized.isBlank()) {
            ragMetrics.recordRequest("stream", "reject");
            ragMetrics.recordRefusal("invalid_request");
            ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart);
            return Flux.just(NO_RESULT_RESPONSE);
        }
        if (scope.isEmptySelection()) {
            ragMetrics.recordRequest("stream", "reject");
            ragMetrics.recordRefusal("invalid_request");
            ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart);
            return Flux.just(emptyScopeResponse(scope));
        }

        try {
            // 1. Query rewrite + 动态参数检索
            long rewriteStart = System.nanoTime();
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<String> attemptedQueries = new ArrayList<>();
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, scope, attemptedQueries);
            long rewriteAndRetrievalMs = (System.nanoTime() - rewriteStart) / 1_000_000;

            if (!hasEffectiveHit(relevantDocs)) {
                emitTrace(trace, question, queryContext, attemptedQueries, relevantDocs,
                    rewriteAndRetrievalMs, 0, NO_RESULT_RESPONSE, "NO_RESULT");
                ragMetrics.recordRequest("stream", "reject");
                ragMetrics.recordRefusal("no_hit");
                ragMetrics.recordStageDuration("rewrite", "reject", queryContext.rewriteDurationMs() * 1_000_000L);
                ragMetrics.recordStageDuration("retrieve", "reject",
                    Math.max(0, rewriteAndRetrievalMs - queryContext.rewriteDurationMs()) * 1_000_000L);
                ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart);
                return Flux.just(withUnavailableHint(NO_RESULT_RESPONSE, scope));
            }

            // 2. 提问计数按实际命中的资料归位
            countService.updateQuestionCounts(hitKnowledgeBaseIds(relevantDocs));

            // 3. 构建上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): scope={}, hitKbIds={}",
                scope.kind(), hitKnowledgeBaseIds(relevantDocs));
            long generationStart = System.nanoTime();
            StringBuilder collectedAnswer = new StringBuilder();
            AtomicBoolean failed = new AtomicBoolean(false);
            // 出处作为最后一小段输出：只在这条回答成功时追加，错误/拒答不追加
            String sourcesBlock = sourcesBlock(relevantDocs);
            Flux<String> answerStream = normalizeStreamOutput(responseFlux)
                .doOnNext(collectedAnswer::append)
                .doOnComplete(() -> {
                    // error 已记录（onErrorResume 转 Flux.just 后仍会走到 complete）时跳过
                    if (!resultRecorded.compareAndSet(false, true)) {
                        return;
                    }
                    long generationNanos = System.nanoTime() - generationStart;
                    boolean rejected = NO_RESULT_RESPONSE.equals(collectedAnswer.toString());
                    String result = rejected ? "reject" : "success";
                    ragMetrics.recordRequest("stream", result);
                    if (rejected) {
                        ragMetrics.recordRefusal("no_hit");
                    }
                    ragMetrics.recordStageDuration("rewrite", result, queryContext.rewriteDurationMs() * 1_000_000L);
                    ragMetrics.recordStageDuration("retrieve", result,
                        Math.max(0, rewriteAndRetrievalMs - queryContext.rewriteDurationMs()) * 1_000_000L);
                    ragMetrics.recordStageDuration("generate", result, generationNanos);
                    ragMetrics.recordStageDuration("total", result, System.nanoTime() - totalStart);
                    emitTrace(trace, question, queryContext, attemptedQueries, relevantDocs,
                        rewriteAndRetrievalMs, generationNanos / 1_000_000, collectedAnswer.toString(),
                        rejected ? "NO_RESULT" : "ANSWERED");
                    log.info("流式输出完成: scope={}, hitKbIds={}", scope.kind(),
                        hitKnowledgeBaseIds(relevantDocs));
                })
                .onErrorResume(e -> {
                    failed.set(true);
                    log.error("流式输出失败: scope={}, error={}", scope.kind(),
                        ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
                    if (resultRecorded.compareAndSet(false, true)) {
                        ragMetrics.recordRequest("stream", "error");
                        ragMetrics.recordStageDuration("generate", "error", System.nanoTime() - generationStart);
                        ragMetrics.recordStageDuration("total", "error", System.nanoTime() - totalStart);
                    }
                    emitTrace(trace, question, queryContext, attemptedQueries, List.of(),
                        rewriteAndRetrievalMs, (System.nanoTime() - generationStart) / 1_000_000,
                        "【错误】知识库查询失败", "ERROR");
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                })
                .doOnCancel(() -> {
                    if (resultRecorded.compareAndSet(false, true)) {
                        ragMetrics.recordRequest("stream", "cancel");
                        ragMetrics.recordStageDuration("generate", "cancel", System.nanoTime() - generationStart);
                        ragMetrics.recordStageDuration("total", "cancel", System.nanoTime() - totalStart);
                    }
                });

            if (sourcesBlock.isBlank()) {
                return answerStream;
            }
            return answerStream.concatWith(Flux.defer(() -> failed.get()
                ? Flux.empty()
                : Flux.just(sourcesBlock)));

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            // 外层 catch 返回的错误文案同样记为 error，不是 success
            if (resultRecorded.compareAndSet(false, true)) {
                ragMetrics.recordRequest("stream", "error");
                ragMetrics.recordStageDuration("total", "error", System.nanoTime() - totalStart);
            }
            emitTrace(trace, normalizeQuestion(question), null, List.of(),
                List.of(), 0, 0, "【错误】知识库查询失败", "ERROR");
            return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
        }
    }

    /**
     * 输出执行轨迹（收集器为 null 时直接返回）。
     */
    private void emitTrace(java.util.function.Consumer<RagQueryExecution> trace,
                           String originalQuestion, QueryContext queryContext,
                           List<String> attemptedQueries, List<Document> chosenDocs,
                           long rewriteAndRetrievalMs, long generationMs,
                           String answer, String outcome) {
        if (trace == null) {
            return;
        }
        List<RagQueryExecution.RetrievedDoc> docs = new ArrayList<>();
        for (int i = 0; i < chosenDocs.size(); i++) {
            Document doc = chosenDocs.get(i);
            docs.add(new RagQueryExecution.RetrievedDoc(
                i + 1, doc.getText(), extractScore(doc), doc.getMetadata()));
        }
        SearchParams params = queryContext != null ? queryContext.searchParams()
            : new SearchParams(topkLong, minScoreDefault);
        long rewriteMs = queryContext != null ? queryContext.rewriteDurationMs() : 0;
        trace.accept(new RagQueryExecution(
            normalizeQuestion(originalQuestion),
            queryContext != null && !queryContext.candidateQueries().isEmpty()
                ? queryContext.candidateQueries().getFirst() : normalizeQuestion(originalQuestion),
            attemptedQueries,
            params.topK(),
            params.minScore(),
            List.copyOf(docs),
            rewriteMs,
            Math.max(0, rewriteAndRetrievalMs - rewriteMs),
            generationMs,
            answer,
            outcome));
    }

    private Double extractScore(Document doc) {
        Double score = doc.getScore();
        if (score != null) {
            return score;
        }
        Object distance = doc.getMetadata().get("distance");
        return distance != null && distance instanceof Number number ? number.doubleValue() : null;
    }


    /**
     * 仅执行检索链路（改写 + 候选检索），不做 LLM 生成。
     * 供 RAG 测评复用生产检索逻辑，避免在测评代码中复制改写与分档规则。
     */
    public RagQueryExecution retrieveOnly(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        String normalized = normalizeQuestion(question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalized.isBlank()) {
            return new RagQueryExecution(normalized, normalized, List.of(normalized),
                topkLong, minScoreDefault, List.of(), 0, 0, 0, NO_RESULT_RESPONSE, "NO_RESULT");
        }
        long start = System.nanoTime();
        List<Message> effectiveHistory = sanitizeHistory(history);
        QueryContext queryContext = buildQueryContext(question, effectiveHistory);
        List<String> attemptedQueries = new ArrayList<>();
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext,
            ResolvedScope.ofSelection(knowledgeBaseIds), attemptedQueries);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        boolean hit = hasEffectiveHit(relevantDocs);
        String stageResult = hit ? "success" : "reject";
        ragMetrics.recordStageDuration("rewrite", stageResult, queryContext.rewriteDurationMs() * 1_000_000L);
        ragMetrics.recordStageDuration("retrieve", stageResult,
            Math.max(0, elapsedMs - queryContext.rewriteDurationMs()) * 1_000_000L);
        return new RagQueryExecution(
            normalized,
            queryContext.candidateQueries().getFirst(),
            List.copyOf(attemptedQueries),
            queryContext.searchParams().topK(),
            queryContext.searchParams().minScore(),
            relevantDocs.stream()
                .map(doc -> new RagQueryExecution.RetrievedDoc(
                    relevantDocs.indexOf(doc) + 1, doc.getText(), extractScore(doc), doc.getMetadata()))
                .toList(),
            queryContext.rewriteDurationMs(),
            Math.max(0, elapsedMs - queryContext.rewriteDurationMs()),
            0,
            null,
            hit ? "RETRIEVED" : "NO_RESULT");
    }

    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        long rewriteStart = System.nanoTime();
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
        long rewriteDurationMs = (System.nanoTime() - rewriteStart) / 1_000_000;
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams, rewriteDurationMs);
    }

    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history;
    }

//       清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

//    向量检索（attemptedQueries 非空时记录实际尝试过的候选，第一个命中后即返回）
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, ResolvedScope scope) {
        return retrieveRelevantDocs(queryContext, scope, null);
    }

    private List<Document> retrieveRelevantDocs(QueryContext queryContext, ResolvedScope scope,
                                                List<String> attemptedQueries) {
        List<String> candidates = queryContext.candidateQueries();
        if (mergeOriginalQuery && candidates.size() > 1
                && !candidates.get(0).equals(candidates.get(1))) {
            return retrieveAndMerge(queryContext, scope, attemptedQueries);
        }
        int topK = queryContext.searchParams().topK();
        int poolSize = poolSize(topK);
        for (int i = 0; i < candidates.size(); i++) {
            String candidateQuery = candidates.get(i);
            if (candidateQuery.isBlank()) {
                continue;
            }
            if (attemptedQueries != null) {
                attemptedQueries.add(candidateQuery);
            }
            long startNanos = System.nanoTime();
            List<Document> candidatesDocs = vectorService.similaritySearch(
                candidateQuery,
                scope.knowledgeBaseIds(),
                poolSize,
                queryContext.searchParams().minScore()
            );
            // 跨库均衡：先按资料保底，再按分数补齐到 topK（单库时结果不变）
            List<Document> docs = balanceByKnowledgeBase(candidatesDocs, topK);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("RAG 检索完成: scope={}, kbCount={}, questionLength={}, queryVariant={}, "
                    + "poolSize={}, hits={}, durationMs={}",
                scope.kind(), scope.knowledgeBaseIds().size(), candidateQuery.length(),
                i == 0 ? "rewritten" : "original", poolSize, docs.size(), durationMs);
            if (hasEffectiveHit(docs)) {
                ragMetrics.recordRetrievalHits("single", docs.size());
                return docs;
            }
        }
        return List.of();
    }

    /**
     * 召回池大小：topK × multiplier，给「按资料保底」这一步留候选。
     * 全库检索时如果某一两份资料霸榜，只有放大召回池才可能把别的资料的片段捞进来。
     */
    private int poolSize(int topK) {
        return topK * candidateMultiplier;
    }

    /**
     * 跨库均衡：每份资料先保底取 {@code perKbQuota} 条，剩余名额按全局分数补齐。
     * <p>
     * 刻意做成「保底」而不是「上限」：范围里只有一份资料时不做任何裁剪，
     * 仍按分数取 topK，避免单库检索被配额误伤。
     * </p>
     * <p>
     * 输入按向量库返回顺序（分数降序）排列。
     * </p>
     */
    List<Document> balanceByKnowledgeBase(List<Document> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || candidates.size() <= topK) {
            return candidates == null ? List.of() : candidates;
        }

        Map<Long, List<Document>> byKnowledgeBase = new LinkedHashMap<>();
        for (Document doc : candidates) {
            Long kbId = knowledgeBaseIdOf(doc);
            if (kbId != null) {
                byKnowledgeBase.computeIfAbsent(kbId, key -> new ArrayList<>()).add(doc);
            }
        }
        // 单库（或拿不到来源）：不做均衡，直接取 topK
        if (byKnowledgeBase.size() <= 1) {
            return candidates.stream().limit(topK).toList();
        }

        List<Document> picked = new ArrayList<>();
        Set<String> pickedIds = new LinkedHashSet<>();
        for (List<Document> docs : byKnowledgeBase.values()) {
            for (int i = 0; i < Math.min(perKbQuota, docs.size()) && picked.size() < topK; i++) {
                Document doc = docs.get(i);
                if (pickedIds.add(doc.getId())) {
                    picked.add(doc);
                }
            }
        }
        for (Document doc : candidates) {
            if (picked.size() >= topK) {
                break;
            }
            if (pickedIds.add(doc.getId())) {
                picked.add(doc);
            }
        }
        return picked.stream()
            .sorted(KnowledgeBaseQueryService::scoreDescendingNullsLast)
            .limit(topK)
            .toList();
    }

    /**
     * 双路召回融合：改写 Query 与原始 Query 各自按相同 Top K/阈值检索一次，
     * 按 Document ID 去重保留高分，null 分数排后、同分保持改写路优先的稳定顺序，
     * 按分数降序截取最终 Top K。两路均来自同一向量库与相似度定义，第一版用最大分数融合。
     */
    private List<Document> retrieveAndMerge(QueryContext queryContext, ResolvedScope scope,
                                            List<String> attemptedQueries) {
        Map<String, Document> merged = new LinkedHashMap<>();
        int topK = queryContext.searchParams().topK();
        int poolSize = poolSize(topK);
        int rewrittenHits = 0;
        int originalHits = 0;
        for (int i = 0; i < 2; i++) {
            String candidateQuery = queryContext.candidateQueries().get(i);
            if (candidateQuery.isBlank()) {
                continue;
            }
            if (attemptedQueries != null) {
                attemptedQueries.add(candidateQuery);
            }
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery, scope.knowledgeBaseIds(), poolSize,
                queryContext.searchParams().minScore());
            if (i == 0) {
                rewrittenHits = docs.size();
            } else {
                originalHits = docs.size();
            }
            for (Document doc : docs) {
                merged.merge(doc.getId(), doc, KnowledgeBaseQueryService::higherScore);
            }
        }
        List<Document> sorted = merged.values().stream()
            .sorted(KnowledgeBaseQueryService::scoreDescendingNullsLast)
            .collect(Collectors.toList());
        List<Document> result = balanceByKnowledgeBase(sorted, topK);
        log.info("RAG 双路融合完成: kbCount={}, rewrittenHits={}, originalHits={}, dedupedHits={}, finalHits={}, topK={}",
            scope.knowledgeBaseIds().size(), rewrittenHits, originalHits, merged.size(),
            result.size(), topK);
        ragMetrics.recordRetrievalHits("rewritten", rewrittenHits);
        ragMetrics.recordRetrievalHits("original", originalHits);
        ragMetrics.recordRetrievalHits("merged", result.size());
        return result;
    }

    private static Document higherScore(Document existing, Document incoming) {
        Double existingScore = existing.getScore();
        Double incomingScore = incoming.getScore();
        if (incomingScore == null) {
            return existing;
        }
        if (existingScore == null || incomingScore > existingScore) {
            return incoming;
        }
        return existing;
    }

    /**
     * 分数降序且 null 分数排最后；分数相同时保持"改写 Query 结果优先"的稳定顺序（稳定排序保证）。
     */
    private static int scoreDescendingNullsLast(Document a, Document b) {
        Double scoreA = a.getScore();
        Double scoreB = b.getScore();
        if (scoreA == null && scoreB == null) {
            return 0;
        }
        if (scoreA == null) {
            return 1;
        }
        if (scoreB == null) {
            return -1;
        }
        return Double.compare(scoreB, scoreA);
    }

    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

//    改写
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled) {
            ragMetrics.recordRewriteFallback("disabled");
            return question;
        }
        if (question.isBlank()) {
            ragMetrics.recordRewriteFallback("blank");
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            if (normalized.equals(question)) {
                ragMetrics.recordRewriteFallback("unchanged");
            }
            log.info("Query rewrite 完成: originLength={}, rewrittenLength={}, changed={}, historySize={}",
                question.length(), normalized.length(), !normalized.equals(question), history.size());
            return normalized;
        } catch (Exception e) {
            ragMetrics.recordRewriteFallback("error");
            log.warn("Query rewrite 失败，使用原问题继续检索: {}",
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            return question;
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    /**
     * 片段来源资料 ID（向量 metadata 里的 kb_id，可能是 Long 也可能是 String）。
     */
    Long knowledgeBaseIdOf(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        Object raw = doc.getMetadata().get("kb_id");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 本轮实际命中的资料（去重，保持首次出现顺序）——用于提问计数归位与出处。
     */
    List<Long> hitKnowledgeBaseIds(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Document doc : docs) {
            Long id = knowledgeBaseIdOf(doc);
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 范围里一份可用资料都没有时的提示：跟「检索没命中」区分开，
     * 否则用户会以为是自己问得不对。
     */
    private String emptyScopeResponse(ResolvedScope scope) {
        if (scope.kind() == RagScopeKind.CATEGORY) {
            String name = scope.description().replaceFirst("^分类：", "");
            return "「" + name + "」分类下还没有可用资料（未上传或未完成向量化），请先补充资料。";
        }
        return "当前会话还没有选择资料，请选择资料或切换为「全部资料」。";
    }

    /**
     * 检索无命中时，如果范围内还有没向量化完的资料，附一句提示。
     */
    private String withUnavailableHint(String message, ResolvedScope scope) {
        String hint = scope.unavailableHint();
        return hint.isBlank() ? message : message + "（" + hint + "）";
    }

    /**
     * 回答末尾的出处块：按命中的资料聚合，只列资料名与片段数。
     * <p>
     * 多库检索时这是用户判断答案可信度的依据；配置项可关闭。
     * </p>
     */
    private String sourcesBlock(List<Document> docs) {
        if (!citeSources || docs == null || docs.isEmpty()) {
            return "";
        }
        Map<Long, Integer> hitsByKnowledgeBase = new LinkedHashMap<>();
        for (Document doc : docs) {
            Long id = knowledgeBaseIdOf(doc);
            if (id != null) {
                hitsByKnowledgeBase.merge(id, 1, Integer::sum);
            }
        }
        if (hitsByKnowledgeBase.isEmpty()) {
            return "";
        }

        Map<Long, String> nameById = knowledgeBaseNames(List.copyOf(hitsByKnowledgeBase.keySet()));

        StringBuilder sb = new StringBuilder("\n\n**出处**\n");
        hitsByKnowledgeBase.forEach((id, count) -> {
            sb.append("- 《").append(nameById.getOrDefault(id, "未知资料")).append("》");
            if (count > 1) {
                sb.append("（").append(count).append(" 段）");
            }
            sb.append('\n');
        });
        return sb.toString();
    }

    /**
     * 资料 ID → 资料名（一次查询，避免逐条查库）。
     */
    private Map<Long, String> knowledgeBaseNames(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<String> names = listService.getKnowledgeBaseNames(ids);
        Map<Long, String> nameById = new HashMap<>();
        for (int i = 0; i < ids.size() && i < names.size(); i++) {
            nameById.put(ids.get(i), names.get(i));
        }
        return nameById;
    }

    /**
     * 只按资料 ID 渲染出处：模型通过工具额外检索到资料、而预检索为空时使用。
     * 这种情况下拿不到每条资料的片段数，所以只列资料名。
     */
    private String sourcesBlockFromKbIds(List<Long> kbIds) {
        if (!citeSources || kbIds == null || kbIds.isEmpty()) {
            return "";
        }
        Map<Long, String> names = knowledgeBaseNames(kbIds);
        StringBuilder sb = new StringBuilder("\n\n**出处**\n");
        for (Long kbId : kbIds) {
            sb.append("- 《").append(names.getOrDefault(kbId, "未知资料")).append("》\n");
        }
        return sb.toString();
    }

    /**
     * 同步问答的出处后缀（判答模板不追加）。
     */
    private String withSources(String answer, List<Document> docs) {
        String block = sourcesBlock(docs);
        return block.isBlank() ? answer : answer + block;
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                sink::error,
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    private record SearchParams(int topK, double minScore) {
    }

    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams,
                                long rewriteDurationMs) {
    }
}
