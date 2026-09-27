package interview.guide.modules.knowledgebase.service.tool;

import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.RagQueryTraceCollector;
import interview.guide.modules.knowledgebase.service.scope.ResolvedScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 资料检索工具：把「查用户自己的资料」暴露给模型。
 * <p>
 * 检索范围不入参——由后端通过 {@link ToolContext} 注入会话已解析的范围，
 * 模型只消费范围，不能改范围。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagKnowledgeSearchTool {

    private static final String TOOL_NAME = "search_knowledge_base";

    private final KnowledgeBaseQueryService queryService;

    @Tool(name = TOOL_NAME, description = """
        在用户自己的资料库里检索与问题相关的原文片段。
        适用：学科知识、讲义/教材内容、用户上传资料里的事实性问题。
        不适用：询问本次对话历史（用 get_conversation_history）、平台功能操作、与资料无关的闲聊。
        同一轮可以多次调用：第一次没命中或结果不足时，换关键词再检索一次。
        """)
    public String searchKnowledgeBase(
        @ToolParam(description = "检索用的关键词或问题，尽量保留学科术语，不要带入「请帮我」这类客套话") String query,
        ToolContext toolContext) {

        ResolvedScope scope = RagToolContexts.scope(toolContext);
        RagQueryTraceCollector collector = RagToolContexts.collector(toolContext);
        long start = System.nanoTime();
        try {
            if (scope == null) {
                return "当前会话没有可用的检索范围。";
            }
            var result = queryService.searchKnowledge(query, scope);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (collector != null) {
                collector.addToolCall(new RagQueryTraceCollector.ToolCall(
                    TOOL_NAME, result.documents().size(), result.topScore(), elapsedMs, false));
                collector.addRetrievedKbIds(result.knowledgeBaseIds());
                collector.updateTopScore(result.topScore());
            }
            if (!result.hit()) {
                return "未在用户资料中检索到与「" + query + "」相关的内容。"
                    + "可以换一个更具体的关键词再检索，或如实告知用户资料里没有这部分。";
            }
            return "检索到 " + result.documents().size() + " 个片段（范围：" + scope.description() + "）：\n\n"
                + result.text();
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("资料检索工具失败: query={}, error={}", query, e.getMessage());
            if (collector != null) {
                collector.addToolCall(new RagQueryTraceCollector.ToolCall(
                    TOOL_NAME, 0, null, elapsedMs, true));
            }
            return "检索资料时出错，请基于已有信息回答，或告知用户稍后再试。";
        }
    }
}
