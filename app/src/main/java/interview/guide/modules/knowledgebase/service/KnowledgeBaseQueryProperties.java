package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    /**
     * RAG 业务指标开关（app.rag.* Micrometer 指标），默认开启。
     */
    private boolean metricsEnabled = true;
    private Rewrite rewrite = new Rewrite();
    private Search search = new Search();
    private History history = new History();
    private Tools tools = new Tools();
    private Trace trace = new Trace();
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    @Data
    public static class Rewrite {
        private boolean enabled = true;
    }

    @Data
    public static class Search {
        /**
         * 双路召回融合：开启后改写 Query 与原始 Query 各自检索一次并按 Document 去重融合。
         * 测评证明整体收益成立前默认关闭。
         */
        private boolean mergeOriginalQuery = false;
        private int shortQueryLength = 4;
        private int topkShort = 20;
        private int topkMedium = 12;
        private int topkLong = 8;
        private double minScoreShort = 0.25;
        private double minScoreDefault = 0.28;

        /**
         * 召回倍数：先按 topK × multiplier 召回，再按资料均衡后收口 topK。
         * 跨库检索时给「每份资料保底」留出候选，最终送进模型的仍然只有 topK 条。
         */
        private int candidateMultiplier = 3;

        /**
         * 每份资料的保底片段数：范围内有多份资料时，保证每份至少有一条进入上下文；
         * 只有一份资料时该值不生效（仍取 topK 条）。
         */
        private int perKbQuota = 2;

        /**
         * 是否在回答末尾附「出处」（资料名 + 片段序号）
         */
        private boolean citeSources = true;
    }

    @Data
    public static class History {
        private boolean enabled = true;
        private int maxMessages = 10;
    }

    /**
     * 工具化路由（function calling）。
     */
    @Data
    public static class Tools {
        /** 关闭后回退到纯 RAG 链路（不挂工具） */
        private boolean enabled = true;
        /** 会话记录工具单次最多返回多少条消息 */
        private int historyToolMaxMessages = 100;
        /** 会话记录工具默认返回条数 */
        private int historyToolDefaultMessages = 20;
        /**
         * 注入给模型的对话记忆窗口（最近多少条消息）。
         * <p>
         * Spring AI 的 MessageWindowChatMemory 只在 add() 时裁剪，而本项目的仓储是
         * 只读的（消息落库由业务侧负责），所以窗口裁剪由仓储在读取时完成，
         * 该值决定「隐式对话上下文」的规模。
         * </p>
         */
        private int chatMemoryMaxMessages = 20;
        /** 单次回答最多允许的工具调用次数（防止来回检索） */
        private int maxToolCalls = 3;
        /** 系统提示词（工具契约版） */
        private String agentSystemPromptPath = "classpath:prompts/knowledgebase-agent-system.st";
        /** 用户提示词（工具契约版：资料片段 + 对话记录 + 问题） */
        private String agentUserPromptPath = "classpath:prompts/knowledgebase-agent-user.st";
    }

    /**
     * 问答轨迹落库（运营榜单数据源）。
     */
    @Data
    public static class Trace {
        private boolean enabled = true;
        /** 榜单默认统计窗口（天） */
        private int defaultWindowDays = 7;
    }
}
