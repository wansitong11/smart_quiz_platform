package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class LlmProviderProperties {
    private String defaultProvider = "dashscope";
    private String defaultEmbeddingProvider;
    private Integer embeddingDimensions = 1024;
    private Map<String, ProviderConfig> providers;
    /**
     * 按 Provider 追加请求体参数（键为 providerId），用于传递各厂商的私有开关。
     * <p>当前用途：DashScope 的 {@code enable_thinking=false}。qwen3.7-plus 默认是推理模型，
     * 切题/批改这类"抽取"任务不需要思考链，实测单批 64s→29s、输出 token 3694→1546。
     * <p>注意：它与 {@code app.ai.providers} 不同，不受数据库里 Provider 配置的影响，
     * 因此改这里一定会生效（Provider 的 model/baseUrl 以数据库为准）。
     */
    private Map<String, Map<String, Object>> providerExtraBody = new HashMap<>();
    private AdvisorConfig advisors = new AdvisorConfig();

    /** 取某个 Provider 需要附加的请求体参数，没有则返回空 Map */
    public Map<String, Object> extraBodyFor(String providerId) {
        Map<String, Object> extraBody = providerExtraBody.get(providerId);
        return extraBody == null ? Map.of() : extraBody;
    }
    private String configYamlPath;
    private String configEnvPath;
    private SecurityConfig security = new SecurityConfig();

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
        private String embeddingModel;
        private Integer embeddingDimensions;
        private Boolean supportsEmbedding;
        private Double temperature;
    }

    @Data
    public static class SecurityConfig {
        private String apiKeyEncryptionKey;
        private boolean requireEncryptionKey = true;
        private boolean allowFallbackEncryptionKey = false;
    }

    @Data
    public static class AdvisorConfig {
        private boolean enabled = true;

        // ToolCallAdvisor
        private boolean toolCallEnabled = true;
        private boolean toolCallConversationHistoryEnabled = false;

        // MessageChatMemoryAdvisor（默认关闭，避免会话串扰）
        private boolean messageChatMemoryEnabled = false;
        private int messageChatMemoryMaxMessages = 120;

        // SimpleLoggerAdvisor（默认关闭）
        private boolean simpleLoggerEnabled = false;

        // SafeGuardAdvisor
        private boolean safeguardEnabled = true;
        private List<String> safeguardWords = List.of(
            "I'll now act as",
            "Sure, I'll ignore",
            "我已经忽略",
            "新的角色是",
            "忽略之前的指令",
            "forget all previous instructions"
        );

        // PromptSanitizer
        private boolean promptSanitizerEnabled = true;
    }
}
