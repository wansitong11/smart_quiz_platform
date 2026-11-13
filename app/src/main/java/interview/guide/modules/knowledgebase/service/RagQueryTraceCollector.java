package interview.guide.modules.knowledgebase.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.ai.document.Document;

/**
 * 一次问答的轨迹收集器：工具在调用过程中往里写，业务在结束时落库。
 * <p>
 * 生命周期 = 一次提问（一次 SSE 请求）。为了简单起见用可变对象，
 * 通过 {@code ToolContext} 传给工具，避免工具再去查一遍上下文。
 * </p>
 */
public class RagQueryTraceCollector {

    private final Long sessionId;
    private final String question;
    private final String scopeDescription;
    private final String llmProvider;
    private final long startedAtNanos = System.nanoTime();

    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final Set<Long> retrievedKbIds = new LinkedHashSet<>();
    private final Map<Long, Set<String>> directHits = new LinkedHashMap<>();

    public synchronized void addDirectHits(List<Document> documents) {
        for (Document document : documents) {
            try {
                long id = Long.parseLong(String.valueOf(document.getMetadata().get("kb_id")));
                if (id > 0) {
                    retrievedKbIds.add(id);
                    directHits.computeIfAbsent(id, key -> new LinkedHashSet<>()).add(document.getId());
                }
            } catch (NumberFormatException exception) {
                continue;
            }
        }
    }

    public synchronized Map<Long, Integer> directHitCounts() {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        directHits.forEach((id, hits) -> counts.put(id, hits.size()));
        return java.util.Collections.unmodifiableMap(counts);
    }

    private String rewrittenQuery;
    private String intent;
    private String outcome;
    private boolean toolError;
    private Double topScore;

    public RagQueryTraceCollector(Long sessionId, String question, String scopeDescription,
                                  String llmProvider) {
        this.sessionId = sessionId;
        this.question = question;
        this.scopeDescription = scopeDescription;
        this.llmProvider = llmProvider;
    }

    /**
     * 单次工具调用记录。
     */
    public record ToolCall(String name, int hits, Double topScore, long latencyMs, boolean error) {
    }

    public synchronized void addToolCall(ToolCall call) {
        toolCalls.add(call);
        if (call.error()) {
            toolError = true;
        }
    }

    public synchronized void addRetrievedKbIds(List<Long> kbIds) {
        if (kbIds != null) {
            retrievedKbIds.addAll(kbIds);
        }
    }

    public synchronized void markIntent(String intent) {
        this.intent = intent;
    }

    public synchronized void markOutcome(String outcome) {
        this.outcome = outcome;
    }

    public synchronized void markRewrittenQuery(String rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    public synchronized void updateTopScore(Double score) {
        if (score == null) {
            return;
        }
        if (topScore == null || score > topScore) {
            topScore = score;
        }
    }

    public long latencyMs() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    public Long sessionId() {
        return sessionId;
    }

    public String question() {
        return question;
    }

    public String scopeDescription() {
        return scopeDescription;
    }

    public String llmProvider() {
        return llmProvider;
    }

    public synchronized String rewrittenQuery() {
        return rewrittenQuery;
    }

    public synchronized String intent() {
        return intent;
    }

    public synchronized String outcome() {
        return outcome;
    }

    public synchronized boolean toolError() {
        return toolError;
    }

    public synchronized Double topScore() {
        return topScore;
    }

    public synchronized List<ToolCall> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public synchronized List<Long> retrievedKbIds() {
        return List.copyOf(retrievedKbIds);
    }
}
