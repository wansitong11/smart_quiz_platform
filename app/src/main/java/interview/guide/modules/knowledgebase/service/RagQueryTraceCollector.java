package interview.guide.modules.knowledgebase.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    public void addToolCall(ToolCall call) {
        toolCalls.add(call);
        if (call.error()) {
            toolError = true;
        }
    }

    public void addRetrievedKbIds(List<Long> kbIds) {
        if (kbIds != null) {
            retrievedKbIds.addAll(kbIds);
        }
    }

    public void markIntent(String intent) {
        this.intent = intent;
    }

    public void markOutcome(String outcome) {
        this.outcome = outcome;
    }

    public void markRewrittenQuery(String rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    public void updateTopScore(Double score) {
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

    public String rewrittenQuery() {
        return rewrittenQuery;
    }

    public String intent() {
        return intent;
    }

    public String outcome() {
        return outcome;
    }

    public boolean toolError() {
        return toolError;
    }

    public Double topScore() {
        return topScore;
    }

    public List<ToolCall> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public List<Long> retrievedKbIds() {
        return List.copyOf(retrievedKbIds);
    }
}
