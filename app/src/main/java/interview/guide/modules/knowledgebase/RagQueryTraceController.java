package interview.guide.modules.knowledgebase;

import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.RagQueryTraceDTO;
import interview.guide.modules.knowledgebase.service.RagQueryTraceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问答轨迹运营榜单接口。
 * <p>
 * 数据来自 {@code rag_query_traces}（每次提问一行明细）：
 * 「拒答率多少」用指标看，「拒答的是哪些问题、该补什么资料」看这里。
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "问答轨迹", description = "问答运营榜单：未命中问题、意图分布、未命中资料")
public class RagQueryTraceController {

    private final RagQueryTraceService traceService;

    /**
     * 路由健康度总览（提问数、拒答率、意图分布 RULE/TOOL/FALLBACK 与平均耗时）
     */
    @GetMapping("/api/rag-chat/traces/summary")
    public Result<RagQueryTraceDTO.TraceSummary> summary(
            @RequestParam(value = "days", required = false, defaultValue = "7") int days) {
        return Result.success(traceService.summarize(days));
    }

    /**
     * 未命中问题榜：查不到答案的问题，按出现次数排序（用来决定补什么资料）
     */
    @GetMapping("/api/rag-chat/traces/no-hit-questions")
    public Result<List<RagQueryTraceDTO.NoHitQuestion>> noHitQuestions(
            @RequestParam(value = "days", required = false, defaultValue = "7") int days,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        return Result.success(traceService.topNoHitQuestions(days, limit));
    }

    /**
     * 从未被命中的资料（可能是分块、命名或内容不适合检索）
     */
    @GetMapping("/api/rag-chat/traces/unused-knowledge-bases")
    public Result<List<RagQueryTraceDTO.UnusedKnowledgeBase>> unusedKnowledgeBases(
            @RequestParam(value = "days", required = false, defaultValue = "7") int days) {
        return Result.success(traceService.unusedKnowledgeBases(days));
    }
}
