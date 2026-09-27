package interview.guide.modules.knowledgebase.service.tool;

import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.RagQueryTraceCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话记录工具：把「本次对话说过什么」暴露给模型。
 * <p>
 * 与检索不同，这类问题是确定性的状态查询（谁来查都一样），所以后端在
 * 规则命中时也会预取一份塞进提示词；工具则覆盖规则没拦到的问法与追问。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagConversationHistoryTool {

    private static final String TOOL_NAME = "get_conversation_history";
    private static final String ORDER_ASC = "ASC";

    private final ConversationHistoryReader historyReader;
    private final KnowledgeBaseQueryProperties properties;

    @Tool(name = TOOL_NAME, description = """
        读取本次会话的历史消息（用户提问与助手回答）。
        适用：用户问「我刚才问了什么」「第一个问题是什么」「上一条回答是什么」「总结一下我们聊的」
        「我们聊过哪些内容」等与对话记录本身有关的问题。
        不适用：学科知识、资料内容、平台功能（那些要用 search_knowledge_base）。
        问「第一个问题」时用 order=ASC，问「最近/上一个」时用 order=DESC。
        """)
    public String getConversationHistory(
        @ToolParam(required = false, description = "返回条数，默认 20，最大 100") Integer limit,
        @ToolParam(required = false, description = "排序：ASC 从最早开始（问第一个问题时用），DESC 从最新开始（默认）") String order,
        ToolContext toolContext) {

        long start = System.nanoTime();
        RagQueryTraceCollector collector = RagToolContexts.collector(toolContext);
        Long sessionId = RagToolContexts.sessionId(toolContext);
        if (sessionId == null) {
            return "无法确定当前会话，读不到对话记录。";
        }

        int maxMessages = properties.getTools().getHistoryToolMaxMessages();
        int defaultMessages = properties.getTools().getHistoryToolDefaultMessages();
        int resolvedLimit = limit == null || limit <= 0 ? defaultMessages : Math.min(limit, maxMessages);
        boolean ascending = ORDER_ASC.equalsIgnoreCase(order == null ? "" : order.trim());

        try {
            List<RagChatMessageEntity> messages = historyReader.read(
                sessionId, resolvedLimit, ascending, true);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (collector != null) {
                collector.addToolCall(new RagQueryTraceCollector.ToolCall(
                    TOOL_NAME, messages.size(), null, elapsedMs, false));
            }
            if (messages.isEmpty()) {
                return "本次会话还没有更早的对话记录。";
            }
            String header = ascending
                ? "本次会话最早 " + messages.size() + " 条消息（按时间正序）：\n\n"
                : "本次会话最近 " + messages.size() + " 条消息（按时间倒序，序号 1 为最新）：\n\n";
            return header + historyReader.render(messages);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("读取会话记录失败: sessionId={}, error={}", sessionId, e.getMessage());
            if (collector != null) {
                collector.addToolCall(new RagQueryTraceCollector.ToolCall(
                    TOOL_NAME, 0, null, elapsedMs, true));
            }
            return "读取对话记录时出错，请告知用户稍后再试。";
        }
    }
}
