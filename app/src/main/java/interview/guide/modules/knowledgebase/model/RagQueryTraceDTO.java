package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 问答轨迹的运营视图（榜单用）。
 */
public class RagQueryTraceDTO {

    /**
     * 未命中问题榜的一项：查不到答案的问题出现了多少次。
     */
    public record NoHitQuestion(
        String question,
        long askTimes,
        LocalDateTime lastSeen
    ) {
    }

    /**
     * 意图分布的一项：RULE / TOOL / FALLBACK 各占多少、平均耗时。
     */
    public record IntentStat(
        String intent,
        long count,
        long avgLatencyMs
    ) {
    }

    /**
     * 路由健康度总览。
     *
     * @param total        统计窗口内的提问数
     * @param noHit        无命中数
     * @param toolError    工具异常数
     * @param refusalRate  拒答率（noHit + toolError / total）
     * @param intents      意图分布
     */
    public record TraceSummary(
        long total,
        long noHit,
        long toolError,
        double refusalRate,
        List<IntentStat> intents
    ) {
    }

    /**
     * 在统计窗口内从未被检索命中的资料（可能是分块、命名或内容问题）。
     */
    public record UnusedKnowledgeBase(
        Long id,
        String name,
        String categoryCode,
        Integer chunkCount
    ) {
    }
}
