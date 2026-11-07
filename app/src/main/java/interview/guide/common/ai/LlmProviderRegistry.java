package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.model.UserModelOverrideEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.repository.UserModelOverrideRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing and caching LLM providers.
 * Supports dynamic creation of ChatClient based on provider configurations.
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    private final LlmProviderProperties properties;
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, OpenAiChatModel> reasoningChatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final UserModelOverrideRepository userModelOverrideRepository;
    private final ApiKeyEncryptionService encryptionService;

    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final ToolCallback interviewSkillsToolCallback;
    private final ChatMemory chatMemory;
    private final AiCallMeter aiCallMeter;
    private AiUserTierResolver aiUserTierResolver;
    private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
        "dashscope", "text-embedding-v3",
        "glm", "embedding-3",
        "zhipu", "embedding-3",
        "baidu", "Embedding-V1",
        "minimax", "embo-01",
        "siliconflow", "BAAI/bge-m3",
        "siliconflow-free", "BAAI/bge-m3"
    );

    @Autowired
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            UserModelOverrideRepository userModelOverrideRepository,
            ApiKeyEncryptionService encryptionService,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback,
            @Autowired(required = false) ChatMemory chatMemory,
            @Autowired(required = false) AiCallMeter aiCallMeter) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.userModelOverrideRepository = userModelOverrideRepository;
        this.encryptionService = encryptionService;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
        this.chatMemory = chatMemory;
        this.aiCallMeter = aiCallMeter;
    }

    public LlmProviderRegistry(
            LlmProviderProperties properties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback) {
        this(properties, null, null, null, null, toolCallingManager, observationRegistry,
            interviewSkillsToolCallback, null, null);
    }

    /**
     * 档位路由是可选的：没有该实现时保持原有“用户绑定 > 全局默认”的行为，
     * 便于单元测试与未启用额度功能的场景。
     */
    @Autowired(required = false)
    public void setAiUserTierResolver(AiUserTierResolver aiUserTierResolver) {
        this.aiUserTierResolver = aiUserTierResolver;
    }

    /**
     * Get a ChatClient for the specified provider ID.
     * If the client is not in the cache, it will be created based on the provider's configuration.
     *
     * @param providerId The ID of the provider (e.g., "dashscope", "lmstudio")
     * @return A ChatClient instance
     * @throws IllegalArgumentException if the providerId is unknown
     */
    public ChatClient getChatClient(String providerId) {
        return clientCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new client for provider: {}", id);
            return createChatClient(id);
        });
    }

    /**
     * Get the default ChatClient based on app.ai.default-provider.
     *
     * @return The default ChatClient instance
     */
    public ChatClient getDefaultChatClient() {
        return getChatClient(resolveChatProviderIdForCurrentUser());
    }

    /**
     * 获取默认 provider 的不带 SkillsTool 的 ChatClient，用于纯粹的摘要 / 结构化文本场景。
     * 与 {@link #getDefaultChatClient()} 的区别在于不挂 Skill 工具与记忆 Advisor，避免无关上下文干扰。
     */
    public ChatClient getPlainChatClient() {
        return getPlainChatClient(resolveChatProviderIdForCurrentUser());
    }

    /**
     * 答案求解客户端：不挂工具和记忆，但允许 Provider 开启思考链。
     * 学生解析生成仍使用 {@link #getPlainChatClient()}。
     */
    public ChatClient getPlainReasoningChatClient() {
        String id = resolveChatProviderIdForCurrentUser();
        return clientCache.computeIfAbsent(id + ":plain:reasoning",
            key -> createPlainReasoningChatClient(id));
    }

    /**
     * Get a ChatClient for the specified provider, falling back to the default if null, blank, or
     * the legacy "default" alias.
     */
    public ChatClient getChatClientOrDefault(String providerId) {
        return getChatClient(resolveProviderId(providerId));
    }

    /**
     * 获取不带 SkillsTool 的 ChatClient，用于结构化输出场景（出题、简历评分等）。
     * 这些场景要求模型一次性返回可解析 JSON，不应混入工具调用消息。
     */
    public ChatClient getPlainChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":plain", key -> createPlainChatClient(id));
    }

    /**
     * 取默认识图模型（app.ai.default-vision-provider / 数据库覆盖）的视觉客户端。
     * 识别服务只依赖这个方法，不感知具体厂商与模型名。
     */
    public ChatClient getDefaultVisionChatClient() {
        return getVisionChatClientOrDefault(null);
    }

    /** 当前视觉默认 Provider 实际使用的模型名（落库展示 / 排障用）。 */
    public String getDefaultVisionModelName() {
        String id = resolveDefaultVisionProviderId();
        if (isBlank(id)) {
            return null;
        }
        return loadProviderOrThrow(id).model();
    }

    /** 当前文本默认 Provider 的模型名（成本估算 / 排障展示用）。 */
    public String getDefaultChatModelName() {
        String id = resolveDefaultChatProviderId();
        if (isBlank(id)) {
            return null;
        }
        return loadProviderOrThrow(id).model();
    }

    /** 当前文本默认 Provider 的 id。 */
    public String getDefaultChatProviderId() {
        return resolveDefaultChatProviderId();
    }

    /**
     * 当前调用上下文实际使用的文本 Provider id（显式指定 &gt; 用户绑定 &gt; 全局默认）。
     * 供需要按模型档位调整运行参数的服务读取，不改变 Provider 选择逻辑。
     */
    public String getCurrentUserChatProviderId() {
        return resolveChatProviderIdForCurrentUser();
    }

    /** 当前调用上下文实际使用的文本模型名（用于免费模型判定 / 成本展示）。 */
    public String getCurrentUserChatModelName() {
        String id = resolveChatProviderIdForCurrentUser();
        if (isBlank(id)) {
            return null;
        }
        return loadProviderOrThrow(id).model();
    }

    /**
     * 当前调用上下文实际使用的识图 Provider id（用户绑定 &gt; 全局默认识图）。
     */
    public String getCurrentUserVisionProviderId() {
        return resolveVisionProviderIdForCurrentUser();
    }

    /** 当前调用上下文实际使用的识图模型名（用于日志 / 落库展示 / 免费判定）。 */
    public String getCurrentUserVisionModelName() {
        String id = resolveVisionProviderIdForCurrentUser();
        if (isBlank(id)) {
            return null;
        }
        return loadProviderOrThrow(id).model();
    }

    /** 当前识图默认 Provider 的 id。 */
    public String getDefaultVisionProviderId() {
        return resolveDefaultVisionProviderId();
    }

    /** 当前 Embedding 默认 Provider 的模型名。 */
    public String getDefaultEmbeddingModelName() {
        String id = resolveDefaultEmbeddingProviderId();
        if (isBlank(id)) {
            return null;
        }
        return loadProviderOrThrow(id).embeddingModel();
    }

    /** 当前 Embedding 默认 Provider 的 id。 */
    public String getDefaultEmbeddingProviderId() {
        return resolveDefaultEmbeddingProviderId();
    }

    /**
     * 视觉任务的独立客户端：单轮、无工具、无记忆、无文本兜底话术。
     * <p>
     * 空白 id 解析成「视觉默认」，不能落到 {@link #getPlainChatClient(String)} ——
     * 后者会把空白解析成 default-chat-provider（通常是纯文本模型）。
     * </p>
     *
     * @param providerId 指定 Provider；空或 "default" 时使用视觉默认
     */
    public ChatClient getVisionChatClientOrDefault(String providerId) {
        String id;
        if (providerId == null || providerId.isBlank()
            || "default".equalsIgnoreCase(providerId.trim())) {
            id = resolveVisionProviderIdForCurrentUser();
        } else {
            id = resolveVisionProviderIdForCurrentUser(providerId.trim());
        }
        if (isBlank(id)) {
            throw new BusinessException(ErrorCode.VISION_PROVIDER_NOT_CONFIGURED,
                "未配置识图模型：请设置 app.ai.default-vision-provider，"
                    + "并确认该 Provider 已启用且标记为支持图片输入");
        }
        assertVisionCapable(id);
        return clientCache.computeIfAbsent(id + ":vision", key -> createVisionChatClient(id));
    }

    /**
     * 获取语音面试专用 ChatClient：SkillsTool + ToolCallAdvisor（流式）。
     * 不加 Memory Advisor（语音面试手动管理对话历史）。
     */
    public ChatClient getVoiceChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":voice", key -> createVoiceChatClient(id));
    }

    /**
     * 获取「工具型 Agent」ChatClient：只挂工具调用 Advisor（支持流式）+ 安全兜底。
     * <p>
     * 与 {@link #getChatClient(String)} 的区别：不挂 SkillsTool、不挂记忆 Advisor——
     * 资料问答场景的工具由调用方按请求注入（工具需要会话上下文），
     * 历史由后端显式管理，避免两套记忆互相干扰。
     * </p>
     */
    public ChatClient getAgentChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":agent", key -> createAgentChatClient(id));
    }

    /**
     * 清空缓存，重新加载所有 provider。
     */
    public void reload() {
        int size = clientCache.size() + chatModelCache.size()
            + reasoningChatModelCache.size() + embeddingModelCache.size();
        clientCache.clear();
        chatModelCache.clear();
        reasoningChatModelCache.clear();
        embeddingModelCache.clear();
        log.info("[LlmProviderRegistry] Cache cleared ({} entries). Next access will re-create clients.", size);
    }

    public EmbeddingModel getEmbeddingModel(String providerId) {
        return embeddingModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id);
            return createEmbeddingModel(id);
        });
    }

    public EmbeddingModel getDefaultEmbeddingModel() {
        return getEmbeddingModel(resolveDefaultEmbeddingProviderId());
    }

    private ChatClient createChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors(providerId);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
            log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size(), providerId);
        }

        attachAiMeter(builder, providerId, "TEXT");
        return builder.build();
    }

    private ChatClient createPlainChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(List.of(advisor)));
        attachAiMeter(builder, providerId, "TEXT");
        log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId);
        return builder.build();
    }

    private ChatClient createPlainReasoningChatClient(String providerId) {
        OpenAiChatModel chatModel = getReasoningChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(List.of(advisor)));
        attachAiMeter(builder, providerId, "TEXT");
        log.info("[LlmProviderRegistry] Created reasoning ChatClient for answer solving: {}", providerId);
        return builder.build();
    }

    /**
     * 视觉客户端走独立工厂：以后要加/去 Advisor、调参、换观测，都不会波及文本链路。
     * <p>
     * 当前刻意不挂任何 Advisor：{@code SafeGuardAdvisor} 的拒答话术面向面试问答，
     * 且会误伤试卷原文（例如阅读材料里出现"忽略之前的指令"）。
     * </p>
     */
    private ChatClient createVisionChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        attachAiMeter(builder, providerId, "VISION");
        log.info("[LlmProviderRegistry] Created vision ChatClient (no advisors) for {}", providerId);
        return builder.build();
    }

    private ChatClient createVoiceChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true));
        }
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        attachAiMeter(builder, providerId, "TEXT");
        log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}", providerId);
        return builder.build();
    }

    /**
     * 工具型 Agent 客户端：流式工具调用 + 安全兜底，工具由调用方按请求注入。
     * <p>
     * 另外挂 {@link MessageChatMemoryAdvisor}：对话上下文由 Spring AI 的 ChatMemory
     * 统一注入（仓储是只读的 {@code ragChatMemoryRepository}，落库仍由业务侧负责）。
     * </p>
     */
    private ChatClient createAgentChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true));
        } else {
            log.warn("[LlmProviderRegistry] Agent client built without ToolCallingManager, provider={}", providerId);
        }
        buildChatMemoryAdvisor().ifPresent(advisors::add);
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        attachAiMeter(builder, providerId, "TEXT");
        log.info("[LlmProviderRegistry] Created agent ChatClient (streaming tools + chat memory) for {}", providerId);
        return builder.build();
    }

    /**
     * 对话记忆 advisor：Spring AI 的 ChatMemory Bean 可用时才挂。
     * <p>
     * ChatMemory 由官方自动配置（{@code ChatMemoryAutoConfiguration}）构建：
     * 它发现有自定义的 {@code ChatMemoryRepository}（本项目是只读适配
     * {@code ragChatMemoryRepository}）就直接用，不再创建内存版实现。
     * </p>
     * <p>
     * <b>为什么不像 {@link #buildDefaultAdvisors} 那样在这里 new 一个
     * {@code MessageWindowChatMemory}</b>：
     * <ul>
     *   <li>{@code MessageWindowChatMemory.get()} 不做裁剪，裁剪只发生在 {@code add()}
     *       之后回写仓储；本项目的对话消息由业务侧写入（仓储是只读的），
     *       所以这里的 maxMessages 对读取路径是无效的——窗口只能由读取侧负责
     *       （已在 {@code RagChatMemoryRepository} 里按 {@code chat-memory-max-messages} 裁剪）；</li>
     *   <li>若在这里再 new 一个（尤其是 {@link #buildDefaultAdvisors} 那种不传仓储的写法），
     *       会退回内存仓储：重启丢历史，且与数据库里的会话记录形成两份状态。</li>
     * </ul>
     * 结论：记忆实例交给自动配置，窗口单一归属「仓储」。
     * </p>
     */
    private Optional<Advisor> buildChatMemoryAdvisor() {
        if (chatMemory == null) {
            return Optional.empty();
        }
        return Optional.of(MessageChatMemoryAdvisor.builder(chatMemory).build());
    }

    private OpenAiChatModel getChatModel(String providerId) {
        return chatModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new ChatModel for provider: {}", id);
            return buildChatModel(id, false);
        });
    }

    private OpenAiChatModel getReasoningChatModel(String providerId) {
        return reasoningChatModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating reasoning ChatModel for provider: {}", id);
            return buildChatModel(id, true);
        });
    }

    private OpenAiChatModel buildChatModel(String providerId, boolean reasoningEnabled) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        long requestTimeout = config.supportsVision()
            ? properties.getVisionRequestTimeout().toMillis()
            : properties.getChatRequestTimeout().toMillis();
        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}, "
                + "Vision: {}, RequestTimeoutMs: {}",
                 providerId, config.baseUrl(), config.model(), config.supportsVision(), requestTimeout);

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(
            config.baseUrl(), config.apiKey(), 10_000, (int) requestTimeout, (int) requestTimeout);

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(config.model())
                .temperature(config.temperature() != null ? config.temperature() : 0.2);
        // Spring AI 的 OpenAiChatOptions 自带 60 秒默认值，并会在每次请求时覆盖
        // OpenAIClient 上的超时；这里必须显式同步，否则 application.yml 的配置不会生效。
        optionsBuilder.timeout(Duration.ofMillis(requestTimeout));

        // 按 Provider 追加请求体参数：例如关闭 DashScope 的推理模式，
        // 否则 qwen3.7-plus 会把大量时间花在思考链上，单次调用轻易超过 60 秒
        Map<String, Object> configuredExtraBody = properties.extraBodyFor(providerId);
        Map<String, Object> extraBody = configuredExtraBody == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(configuredExtraBody);
        if (reasoningEnabled && providerId.toLowerCase().contains("dashscope")) {
            extraBody.put("enable_thinking", true);
        }
        if (reasoningEnabled && providerId.toLowerCase(Locale.ROOT).startsWith("glm")) {
            // 普通结构化调用在 provider-extra-body 里关闭思考；
            // 答案复核用的 reasoning 客户端必须显式打开，否则智谱会沿用 disabled。
            extraBody.put("thinking", Map.of("type", "enabled"));
        }
        if (!extraBody.isEmpty()) {
            optionsBuilder.extraBody(extraBody);
            log.info("[LlmProviderRegistry] Provider {} 追加请求参数: {}", providerId, extraBody);
        }
        OpenAiChatOptions options = optionsBuilder.build();

        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    private EmbeddingModel createEmbeddingModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }
        if (looksLikeChatModel(config.embeddingModel())) {
            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
            String suffix = recommendation != null
                ? "，推荐填写 " + recommendation
                : "，请填写该厂商真实的 Embedding 模型名";
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 的 Embedding Model 配成了聊天模型 '"
                    + config.embeddingModel() + "'" + suffix);
        }
        log.info("[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl(), config.embeddingModel());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());
        OpenAiEmbeddingOptions options = supportsEmbeddingDimensionOverride(config.embeddingModel())
            ? OpenAiEmbeddingOptions.builder()
                .model(config.embeddingModel())
                .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
                .build()
            : OpenAiEmbeddingOptions.builder()
                .model(config.embeddingModel())
                .build();

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
            .openAiClient(openAiClient)
            .metadataMode(MetadataMode.EMBED)
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
        if (aiCallMeter != null) {
            return aiCallMeter.wrapEmbedding(embeddingModel, providerId, config.embeddingModel());
        }
        return embeddingModel;
    }

    private void attachAiMeter(ChatClient.Builder builder, String providerId, String modality) {
        if (aiCallMeter == null) {
            return;
        }
        String model = loadProviderOrThrow(providerId).model();
        builder.defaultAdvisors(aiCallMeter.chatAdvisor(providerId, model, modality));
    }

    private List<Advisor> buildDefaultAdvisors(String providerId) {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();

        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                advisors.add(buildToolCallAdvisor(config.isToolCallConversationHistoryEnabled()));
            } else {
                log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}", providerId);
            }
        }

        if (config.isMessageChatMemoryEnabled()) {
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                    .maxMessages(maxMessages)
                    .build()
            ).build();
            advisors.add(memoryAdvisor);
        }

        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        buildSafeGuardAdvisor().ifPresent(advisors::add);

        return advisors;
    }

    private ToolCallingAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled) {
        return ToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .conversationHistoryEnabled(conversationHistoryEnabled)
            .build();
    }

    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
            .sensitiveWords(config.getSafeguardWords())
            .failureResponse("抱歉，我只能协助面试相关的任务。")
            .order(100)
            .build();
        return Optional.of(advisor);
    }

    private String resolveProviderId(String providerId) {
        Long userId = currentUserId();
        if (userId != null && aiUserTierResolver != null
            && aiUserTierResolver.isFreeTier(userId)) {
            String freeProvider = properties.getTier().getFreeTextProvider();
            if (!isBlank(freeProvider) && isProviderUsable(freeProvider)) {
                return freeProvider;
            }
        }
        if (userId != null && aiUserTierResolver != null
            && aiUserTierResolver.isAuthorizedTier(userId)) {
            return resolveDefaultChatProviderId();
        }
        if (providerId == null || providerId.isBlank() || "default".equalsIgnoreCase(providerId.trim())) {
            return resolveChatProviderIdForCurrentUser();
        }
        return providerId;
    }

    private String resolveDefaultChatProviderId() {
        if (globalSettingRepository == null) {
            return properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
            .filter(id -> !isBlank(id))
            .orElse(properties.getDefaultProvider());
    }

    private String resolveDefaultEmbeddingProviderId() {
        if (globalSettingRepository == null) {
            return !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
            .filter(id -> !isBlank(id))
            .orElseGet(() -> !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider());
    }

    /**
     * 视觉默认值：数据库覆盖 → yml 配置 → 空（由调用方抛 VISION_PROVIDER_NOT_CONFIGURED）。
     * 不继承 default-chat-provider / default-embedding-provider：三者语义不同。
     */
    private String resolveDefaultVisionProviderId() {
        if (globalSettingRepository != null) {
            String fromDb = globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
                .map(LlmGlobalSettingEntity::getDefaultVisionProviderId)
                .filter(id -> !isBlank(id))
                .orElse(null);
            if (!isBlank(fromDb)) {
                return fromDb;
            }
        }
        return properties.getDefaultVisionProvider();
    }

    /**
     * 当前用户的文字 Provider：档位路由优先，之后是用户绑定（TEXT）与全局默认。
     * 绑定每次现查数据库，改绑定立即生效，不做进程内缓存。
     */
    private String resolveChatProviderIdForCurrentUser() {
        return resolveChatProviderIdForUser(currentUserId());
    }

    /**
     * 解析指定用户实际使用的文字 Provider。
     *
     * <p>供管理端展示“实际生效模型”使用；路由顺序与当前调用上下文完全一致：
     * 免费档路由 / 授权档路由优先，之后才是用户绑定和全局默认。</p>
     */
    public String resolveChatProviderIdForUser(Long userId) {
        if (userId != null && aiUserTierResolver != null
            && aiUserTierResolver.isFreeTier(userId)) {
            String freeProvider = properties.getTier().getFreeTextProvider();
            if (!isBlank(freeProvider) && isProviderUsable(freeProvider)) {
                return freeProvider;
            }
        }
        if (userId != null && aiUserTierResolver != null
            && aiUserTierResolver.isAuthorizedTier(userId)) {
            return resolveDefaultChatProviderId();
        }
        if (userId != null && userModelOverrideRepository != null) {
            String override = userModelOverrideRepository
                .findByUserIdAndModality(userId, UserModelOverrideEntity.TEXT)
                .map(UserModelOverrideEntity::getProviderId)
                .filter(id -> !isBlank(id))
                .orElse(null);
            if (override != null) {
                if (isProviderUsable(override)) {
                    return override;
                }
                log.warn("用户 {} 的文字模型绑定 {} 不可用，回退全局默认", userId, override);
            }
        }
        return resolveDefaultChatProviderId();
    }

    /**
     * 当前用户的识图 Provider：档位路由优先，之后是用户绑定（VISION）与全局默认识图。
     */
    private String resolveVisionProviderIdForCurrentUser() {
        return resolveVisionProviderIdForUser(currentUserId());
    }

    private String resolveVisionProviderIdForCurrentUser(String explicitProviderId) {
        return resolveVisionProviderIdForUser(currentUserId(), explicitProviderId);
    }

    /** 解析指定用户实际使用的识图 Provider；路由顺序与当前调用上下文一致。 */
    public String resolveVisionProviderIdForUser(Long userId) {
        return resolveVisionProviderIdForUser(userId, null);
    }

    private String resolveVisionProviderIdForUser(Long userId, String explicitProviderId) {
        if (userId != null && aiUserTierResolver != null
            && aiUserTierResolver.isFreeTier(userId)) {
            String freeProvider = properties.getTier().getFreeVisionProvider();
            if (!isBlank(freeProvider) && isVisionProviderUsable(freeProvider)) {
                return freeProvider;
            }
        }
        if (userId != null && aiUserTierResolver != null
            && aiUserTierResolver.isAuthorizedTier(userId)) {
            return resolveDefaultVisionProviderId();
        }
        if (userId != null && userModelOverrideRepository != null) {
            String override = userModelOverrideRepository
                .findByUserIdAndModality(userId, UserModelOverrideEntity.VISION)
                .map(UserModelOverrideEntity::getProviderId)
                .filter(id -> !isBlank(id))
                .orElse(null);
            if (override != null) {
                if (isVisionProviderUsable(override)) {
                    return override;
                }
                log.warn("用户 {} 的识图模型绑定 {} 不可用，回退全局默认", userId, override);
            }
        }
        if (!isBlank(explicitProviderId) && isVisionProviderUsable(explicitProviderId)) {
            return explicitProviderId;
        }
        return resolveDefaultVisionProviderId();
    }

    private Long currentUserId() {
        AiCallContextHolder.AiCallContext context = AiCallContextHolder.get();
        return context == null ? null : context.userId();
    }

    private boolean isProviderUsable(String providerId) {
        if (providerRepository == null) {
            return properties.getProviders().containsKey(providerId);
        }
        return providerRepository.findById(providerId)
            .map(LlmProviderEntity::isEnabled)
            .orElse(false);
    }

    private boolean isVisionProviderUsable(String providerId) {
        if (providerRepository == null) {
            ProviderConfig config = properties.getProviders().get(providerId);
            return config != null && Boolean.TRUE.equals(config.getSupportsVision());
        }
        return providerRepository.findById(providerId)
            .filter(LlmProviderEntity::isEnabled)
            .map(LlmProviderEntity::isSupportsVision)
            .orElse(false);
    }

    /**
     * 校验视觉能力：不存在 / 已禁用 / 未声明 supports-vision 都给出可操作的错误，
     * 而不是把图片发给文本模型后拿厂商 400。
     */
    private void assertVisionCapable(String providerId) {
        ProviderSnapshot snapshot;
        try {
            snapshot = loadProviderOrThrow(providerId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VISION_PROVIDER_NOT_CONFIGURED,
                "识图 Provider '" + providerId + "' 不存在或已禁用，请在「设置 → 模型 Provider」中检查");
        }
        if (!snapshot.supportsVision()) {
            throw new BusinessException(ErrorCode.VISION_PROVIDER_NOT_CONFIGURED,
                "Provider '" + providerId + "' 的模型 '" + snapshot.model()
                    + "' 未标记为支持图片输入，请更换为视觉模型并勾选「支持图片输入」");
        }
    }

    private ProviderSnapshot loadProviderOrThrow(String providerId) {
        if (providerRepository == null) {
            return loadProviderFromPropertiesOrThrow(providerId);
        }
        LlmProviderEntity entity = providerRepository.findById(providerId)
            .filter(LlmProviderEntity::isEnabled)
            .orElseThrow(() -> new IllegalArgumentException("Unknown LLM provider: " + providerId));
        return new ProviderSnapshot(
            entity.getId(),
            entity.getBaseUrl(),
            encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
            entity.getModel(),
            entity.getEmbeddingModel(),
            entity.getEmbeddingDimensions(),
            entity.isSupportsEmbedding(),
            entity.isSupportsVision(),
            entity.getTemperature()
        );
    }

    private ProviderSnapshot loadProviderFromPropertiesOrThrow(String providerId) {
        ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }
        boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
            || !isBlank(config.getEmbeddingModel());
        return new ProviderSnapshot(
            providerId,
            config.getBaseUrl(),
            config.getApiKey(),
            config.getModel(),
            config.getEmbeddingModel(),
            config.getEmbeddingDimensions(),
            supportsEmbedding,
            Boolean.TRUE.equals(config.getSupportsVision()),
            config.getTemperature()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    /**
     * 硅基流动文档明确 dimensions 仅 Qwen/Qwen3 Embedding 系列支持；
     * BGE / BCE 这类固定维度模型不能带该参数，否则可能直接 400。
     */
    static boolean supportsEmbeddingDimensionOverride(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return !(normalized.contains("bge-m3")
            || normalized.contains("bge-large")
            || normalized.contains("bce-embedding"));
    }

    private boolean looksLikeChatModel(String model) {
        String lower = model.toLowerCase();
        return lower.startsWith("glm-")
            || lower.startsWith("deepseek")
            || lower.startsWith("kimi")
            || lower.startsWith("moonshot")
            || lower.startsWith("qwen")
            || lower.startsWith("ernie");
    }

    private record ProviderSnapshot(
        String id,
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        boolean supportsEmbedding,
        boolean supportsVision,
        Double temperature
    ) {
    }
}
