package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderBootstrapService {

  private final LlmProviderProperties properties;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;

  @PostConstruct
  @Transactional
  public void seedProvidersIfNecessary() {
    // 只补 yml 中缺失的 Provider，已存在的行不覆盖（数据库仍是权威来源）。
    // 这条是"新增视觉 Provider 后旧库无需手工建行"的落地方式。
    seedProviders();
    warnIfConfiguredModelDiffers();
    ensureGlobalSetting();
  }

  /**
   * Provider 一旦落库，数据库就是唯一权威来源：application.yml / AI_MODEL 的后续修改不再生效
   * （这是刻意的——界面上改过的模型不能被重启覆盖）。但这条规则非常反直觉，
   * 所以启动时把"配置里写的模型"与"实际生效的模型"不一致的地方显式打出来，
   * 免得改了 AI_MODEL 却一直在用旧模型（例如旧模型免费额度已耗尽，表现为 403）。
   */
  private void warnIfConfiguredModelDiffers() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null || providers.isEmpty()) {
      return;
    }
    providers.forEach((id, config) -> {
      if (config == null || isBlank(config.getModel())) {
        return;
      }
      providerRepository.findById(id).ifPresent(entity -> {
        if (!config.getModel().equals(entity.getModel())) {
          log.warn("Provider '{}' 实际生效的模型是 '{}'（来自数据库 llm_provider_config），"
                  + "与配置里的 '{}' 不一致。运行时以数据库为准："
                  + "请在「设置 → 模型 Provider」里修改，或更新该表后调用 /api/llm-provider/reload。",
              id, entity.getModel(), config.getModel());
        }
      });
    });
  }

  private void seedProviders() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null || providers.isEmpty()) {
      log.warn("No app.ai.providers seed configuration found");
      return;
    }

    int[] added = {0};
    providers.forEach((id, config) -> {
      if (isBlank(id) || config == null || isBlank(config.getBaseUrl()) || isBlank(config.getModel())) {
        log.warn("Skip invalid provider seed: id={}", id);
        return;
      }
      if (providerRepository.existsById(id)) {
        return;
      }
      ApiKeyEncryptionService.EncryptedValue encrypted =
          encryptionService.encrypt(config.getApiKey() != null ? config.getApiKey() : "");
      boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
          || !isBlank(config.getEmbeddingModel());

      LlmProviderEntity entity = LlmProviderEntity.builder()
          .id(id)
          .baseUrl(config.getBaseUrl())
          .apiKeyNonce(encrypted.nonce())
          .apiKeyCiphertext(encrypted.ciphertext())
          .model(config.getModel())
          .embeddingModel(trimOrNull(config.getEmbeddingModel()))
          .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
          .supportsEmbedding(supportsEmbedding)
          .supportsVision(Boolean.TRUE.equals(config.getSupportsVision()))
          .temperature(config.getTemperature())
          .enabled(true)
          .builtin(true)
          .build();
      providerRepository.save(entity);
      added[0]++;
      log.info("从配置补入缺失的 Provider: id={}, model={}, supportsVision={}",
          id, config.getModel(), Boolean.TRUE.equals(config.getSupportsVision()));
    });
    log.info("LLM Provider 种子检查完成: 新增 {} 个, 当前共 {} 个",
        added[0], providerRepository.count());
  }

  private void ensureGlobalSetting() {
    if (globalSettingRepository.existsById(LlmGlobalSettingEntity.SINGLETON_ID)) {
      LlmGlobalSettingEntity setting = globalSettingRepository
          .findById(LlmGlobalSettingEntity.SINGLETON_ID).orElse(null);
      if (setting != null && isBlank(setting.getDefaultVisionProviderId())
          && !isBlank(properties.getDefaultVisionProvider())) {
        setting.setDefaultVisionProviderId(properties.getDefaultVisionProvider());
        globalSettingRepository.save(setting);
        log.info("补充默认识图 Provider: {}", properties.getDefaultVisionProvider());
      }
      return;
    }
    String defaultChatProvider = resolveExistingProvider(
        properties.getDefaultProvider(),
        providerRepository.findAll().stream().findFirst().map(LlmProviderEntity::getId).orElse("dashscope")
    );
    String configuredEmbeddingProvider = !isBlank(properties.getDefaultEmbeddingProvider())
        ? properties.getDefaultEmbeddingProvider()
        : defaultChatProvider;
    String defaultEmbeddingProvider = resolveExistingEmbeddingProvider(configuredEmbeddingProvider, defaultChatProvider);

    globalSettingRepository.save(LlmGlobalSettingEntity.builder()
        .id(LlmGlobalSettingEntity.SINGLETON_ID)
        .defaultChatProviderId(defaultChatProvider)
        .defaultEmbeddingProviderId(defaultEmbeddingProvider)
        .defaultVisionProviderId(properties.getDefaultVisionProvider())
        .build());
    log.info("Initialized LLM global setting: chatProvider={}, embeddingProvider={}",
        defaultChatProvider, defaultEmbeddingProvider);
  }

  private String resolveExistingProvider(String preferredProvider, String fallbackProvider) {
    if (!isBlank(preferredProvider) && providerRepository.existsById(preferredProvider)) {
      return preferredProvider;
    }
    return fallbackProvider;
  }

  private String resolveExistingEmbeddingProvider(String preferredProvider, String fallbackProvider) {
    return providerRepository.findById(preferredProvider)
        .filter(this::canProvideEmbedding)
        .map(LlmProviderEntity::getId)
        .orElseGet(() -> providerRepository.findAll().stream()
            .filter(this::canProvideEmbedding)
            .findFirst()
            .map(LlmProviderEntity::getId)
            .orElse(fallbackProvider));
  }

  private boolean canProvideEmbedding(LlmProviderEntity provider) {
    return provider.isEnabled()
        && provider.isSupportsEmbedding()
        && !isBlank(provider.getEmbeddingModel());
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
