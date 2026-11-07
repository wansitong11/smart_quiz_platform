package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class LlmProviderProperties {
    private String defaultProvider = "dashscope";
    private String defaultEmbeddingProvider;
    /**
     * 识图任务的默认 Provider。独立于文本默认：文本模型不接收图片，
     * 混用只会在厂商侧拿到 400。为空表示"尚未配置识图模型"。
     */
    private String defaultVisionProvider;
    private Integer embeddingDimensions = 1024;
    /**
     * 单次模型调用的整体超时（对应 OkHttp callTimeout，包含等待响应头）。
     * 不设时 HTTP/2 连接只要还有 PING 保活，readTimeout 不会触发，调用会永久挂住。
     */
    private Duration chatRequestTimeout = Duration.ofMinutes(5);
    /** 识图单页调用的整体超时：正常 20~70 秒，2 分钟足够，卡住也不会堵死整个识别队列。 */
    private Duration visionRequestTimeout = Duration.ofMinutes(2);
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
    /** 零价格模型 Provider 标识，供免费模型并发闸门判定；不再自动修改全局默认。 */
    private FreeModels freeModels = new FreeModels();

    /**
     * 档位路由：免费档用户固定走这里指定的 Provider；授权用户与系统任务走全局默认。
     * 真正的档位判定由 common 的 {@code AiUserTierResolver} 提供，这里只保存目标 Provider。
     */
    private TierRouting tier = new TierRouting();

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
        private Boolean supportsVision;
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

    @Data
    public static class FreeModels {
        private String textProvider = "siliconflow-free";
        private String visionProvider = "glm-vl-free";
        private String embeddingProvider = "siliconflow-free";
        /** 语音 ASR/TTS 的免费 Provider；为空表示只按价格表判断音频是否免费。 */
        private String audioProvider = "";
    }

    @Data
    public static class TierRouting {
        /** 免费档文本 Provider；不存在/不可用时回退全局默认。 */
        private String freeTextProvider = "dashscope-3.7";
        /** 免费档识图 Provider；不存在/不可用时回退全局默认。 */
        private String freeVisionProvider = "dashscope-3.7-vl";
    }
}
