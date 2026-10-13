package interview.guide.modules.knowledgebase.model;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 一次问答的明细轨迹。
 * <p>
 * 与 {@code RagMetrics} 的聚合指标互补：指标回答「拒答率多少」，
 * 轨迹回答「拒答的是哪些问题」——运营榜单（未命中问题榜、意图分布、路由健康度）
 * 都建立在这张表上。
 * </p>
 */
@Entity
@Table(name = "rag_query_traces", indexes = {
    @Index(name = "idx_rag_trace_created", columnList = "createdAt"),
    @Index(name = "idx_rag_trace_outcome", columnList = "outcome")
})
@Getter
@Setter
public class RagQueryTraceEntity {

    /** 归属用户：轨迹只对本人生效（管理员 scope=all 例外） */
    @Column(name = "owner_id")
    private Long ownerId;

    /** 结果：正常回答 / 无命中 / 工具异常 */
    public static final String OUTCOME_ANSWERED = "ANSWERED";
    public static final String OUTCOME_NO_HIT = "NO_HIT";
    public static final String OUTCOME_TOOL_ERROR = "TOOL_ERROR";

    /**
     * 路由来源：模型是否调用了工具。
     * <p>
     * 对话历史不再走规则前置——由 ChatMemory（最近若干轮）与
     * {@code get_conversation_history} 工具（全量/定向查询）共同承担。
     * </p>
     */
    public static final String INTENT_TOOL = "TOOL";
    /** 走预检索通道（模型没有额外调工具） */
    public static final String INTENT_PRERETRIEVAL = "PRERETRIEVAL";
    /** 兜底：模型没调工具，后端补一次检索 */
    public static final String INTENT_FALLBACK = "FALLBACK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(length = 500)
    private String question;

    @Column(name = "rewritten_query", length = 500)
    private String rewrittenQuery;

    /** 命中的检索范围描述（全部资料 / 分类：数学 / 3 份指定资料） */
    @Column(name = "scope_description", length = 128)
    private String scopeDescription;

    /** 路由来源：RULE / TOOL / FALLBACK */
    @Column(length = 16)
    private String intent;

    /** 工具调用明细 JSON：[{"name":"search_knowledge_base","hits":6,"topScore":0.71,"ms":380}] */
    @Column(name = "tools_json", columnDefinition = "TEXT")
    private String toolsJson;

    /** 本轮命中的资料 ID（逗号分隔），用于「从没被命中的资料」榜单 */
    @Column(name = "retrieved_kb_ids", length = 255)
    private String retrievedKbIds;

    @Column(name = "top_score")
    private Double topScore;

    /** ANSWERED / NO_HIT / TOOL_ERROR */
    @Column(length = 20)
    private String outcome;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "llm_provider", length = 50)
    private String llmProvider;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getRewrittenQuery() {
        return rewrittenQuery;
    }

    public void setRewrittenQuery(String rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    public String getScopeDescription() {
        return scopeDescription;
    }

    public void setScopeDescription(String scopeDescription) {
        this.scopeDescription = scopeDescription;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getToolsJson() {
        return toolsJson;
    }

    public void setToolsJson(String toolsJson) {
        this.toolsJson = toolsJson;
    }

    public String getRetrievedKbIds() {
        return retrievedKbIds;
    }

    public void setRetrievedKbIds(String retrievedKbIds) {
        this.retrievedKbIds = retrievedKbIds;
    }

    public Double getTopScore() {
        return topScore;
    }

    public void setTopScore(Double topScore) {
        this.topScore = topScore;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
