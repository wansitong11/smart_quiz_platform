package interview.guide.modules.knowledgebase.service.tool;

/**
 * 通过 Spring AI {@code ToolContext} 传给工具的隐式上下文键。
 * <p>
 * 这些值模型看不到（不占用工具参数），由后端在发起请求时注入，
 * 例如会话 ID 与已经解析好的检索范围——模型只消费范围，不能改范围。
 * </p>
 */
public final class RagToolContextKeys {

    /** 会话 ID（Long） */
    public static final String SESSION_ID = "sessionId";

    /** 已解析的检索范围：{@code ResolvedScope} */
    public static final String SCOPE = "scope";

    /** 轨迹收集器：{@code RagQueryTraceCollector} */
    public static final String COLLECTOR = "collector";

    private RagToolContextKeys() {
    }
}
