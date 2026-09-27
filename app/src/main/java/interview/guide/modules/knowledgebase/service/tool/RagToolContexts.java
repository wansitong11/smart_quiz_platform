package interview.guide.modules.knowledgebase.service.tool;

import interview.guide.modules.knowledgebase.service.RagQueryTraceCollector;
import interview.guide.modules.knowledgebase.service.scope.ResolvedScope;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 从 ToolContext 里读取隐式上下文（这些值模型看不到，不占用工具入参）。
 */
public final class RagToolContexts {

    private RagToolContexts() {
    }

    public static Long sessionId(ToolContext toolContext) {
        Object value = value(toolContext, RagToolContextKeys.SESSION_ID);
        return value instanceof Number number ? number.longValue() : null;
    }

    public static ResolvedScope scope(ToolContext toolContext) {
        Object value = value(toolContext, RagToolContextKeys.SCOPE);
        return value instanceof ResolvedScope scope ? scope : null;
    }

    public static RagQueryTraceCollector collector(ToolContext toolContext) {
        Object value = value(toolContext, RagToolContextKeys.COLLECTOR);
        return value instanceof RagQueryTraceCollector collector ? collector : null;
    }

    private static Object value(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        return toolContext.getContext().get(key);
    }
}
