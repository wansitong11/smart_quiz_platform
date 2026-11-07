package interview.guide.modules.llmprovider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("LLM Provider 启动初始化")
class LlmProviderBootstrapServiceTest {

  @Mock
  private LlmProviderRepository providerRepository;
  @Mock
  private LlmGlobalSettingRepository globalSettingRepository;
  @Mock
  private ApiKeyEncryptionService encryptionService;

  private LlmProviderProperties properties;
  private LlmProviderBootstrapService service;

  @BeforeEach
  void setUp() {
    properties = new LlmProviderProperties();
    properties.setProviders(Map.of(
        "glm-free", provider("https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash"),
        "glm-vl-free", visionProvider("https://open.bigmodel.cn/api/paas/v4", "glm-4.6v-flash"),
        "siliconflow-free", embeddingProvider()
    ));
    service = new LlmProviderBootstrapService(
        properties, providerRepository, globalSettingRepository, encryptionService);
  }

  @Test
  @DisplayName("启动时不再自动把全局默认切到免费 Provider")
  void keepsConfiguredDefaultsEvenWhenFreeProvidersHaveKeys() {
    LlmGlobalSettingEntity setting = setting("dashscope", "dashscope", "dashscope-vl");
    stubExistingSetting(setting);

    service.seedProvidersIfNecessary();

    assertThat(setting.getDefaultChatProviderId()).isEqualTo("dashscope");
    assertThat(setting.getDefaultVisionProviderId()).isEqualTo("dashscope-vl");
    assertThat(setting.getDefaultEmbeddingProviderId()).isEqualTo("dashscope");
    verify(globalSettingRepository, never()).save(setting);
  }

  @Test
  @DisplayName("Key 为空时保持原有默认模型，不切换到空 Key Provider")
  void keepsOriginalDefaultsWhenKeysAreBlank() {
    LlmGlobalSettingEntity setting = setting("dashscope", "dashscope", "dashscope-vl");
    stubExistingSetting(setting);

    service.seedProvidersIfNecessary();

    assertThat(setting.getDefaultChatProviderId()).isEqualTo("dashscope");
    assertThat(setting.getDefaultVisionProviderId()).isEqualTo("dashscope-vl");
    verify(globalSettingRepository, never()).save(any());
  }

  @Test
  @DisplayName("配置补全空 API Key，且不覆盖数据库里已有的非空 Key")
  void fillsBlankApiKeyFromConfigOnly() {
    properties.getProviders().get("glm-free").setApiKey("new-key");
    properties.getProviders().get("glm-vl-free").setApiKey("existing-config-key");

    LlmProviderEntity blankKeyProvider = providerEntity("glm-free", "n1", "c1");
    LlmProviderEntity existingKeyProvider = providerEntity("glm-vl-free", "n2", "c2");
    LlmGlobalSettingEntity setting = setting("dashscope", "dashscope", "dashscope-vl");
    stubExistingSetting(setting);
    when(providerRepository.existsById(anyString())).thenReturn(true);
    when(providerRepository.findById("glm-free")).thenReturn(Optional.of(blankKeyProvider));
    when(providerRepository.findById("glm-vl-free")).thenReturn(Optional.of(existingKeyProvider));
    when(encryptionService.decrypt("n1", "c1")).thenReturn("");
    when(encryptionService.decrypt("n2", "c2")).thenReturn("db-key");
    when(encryptionService.encrypt("new-key"))
        .thenReturn(new ApiKeyEncryptionService.EncryptedValue("n-new", "c-new"));

    service.seedProvidersIfNecessary();

    assertThat(blankKeyProvider.getApiKeyNonce()).isEqualTo("n-new");
    assertThat(blankKeyProvider.getApiKeyCiphertext()).isEqualTo("c-new");
    assertThat(existingKeyProvider.getApiKeyNonce()).isEqualTo("n2");
    assertThat(existingKeyProvider.getApiKeyCiphertext()).isEqualTo("c2");
    verify(providerRepository).save(blankKeyProvider);
    verify(providerRepository, never()).save(existingKeyProvider);
  }

  @Test
  @DisplayName("首次启动把免费 Provider 配置写入数据库")
  void seedsFreeProvidersIntoDatabase() {
    when(providerRepository.existsById(anyString())).thenReturn(false);
    when(providerRepository.count()).thenReturn(3L);
    when(encryptionService.encrypt(anyString()))
        .thenReturn(new ApiKeyEncryptionService.EncryptedValue("nonce", "cipher"));
    LlmGlobalSettingEntity setting = setting("dashscope", "dashscope", "dashscope-vl");
    when(globalSettingRepository.existsById(LlmGlobalSettingEntity.SINGLETON_ID)).thenReturn(true);
    when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(Optional.of(setting));

    service.seedProvidersIfNecessary();

    ArgumentCaptor<LlmProviderEntity> captor = ArgumentCaptor.forClass(LlmProviderEntity.class);
    verify(providerRepository, times(3)).save(captor.capture());
    Map<String, LlmProviderEntity> saved = captor.getAllValues().stream()
        .collect(Collectors.toMap(LlmProviderEntity::getId, entity -> entity));
    assertThat(saved).containsKeys("glm-free", "glm-vl-free", "siliconflow-free");
    assertThat(saved.get("glm-free").getModel()).isEqualTo("glm-4.7-flash");
    assertThat(saved.get("glm-vl-free").getModel()).isEqualTo("glm-4.6v-flash");
    assertThat(saved.get("glm-vl-free").isSupportsVision()).isTrue();
    assertThat(saved.get("siliconflow-free").getEmbeddingModel()).isEqualTo("BAAI/bge-m3");
    assertThat(saved.get("siliconflow-free").getEmbeddingDimensions()).isEqualTo(1024);
  }

  private void stubExistingSetting(LlmGlobalSettingEntity setting) {
    when(providerRepository.existsById(anyString())).thenReturn(true);
    when(providerRepository.count()).thenReturn(3L);
    when(providerRepository.findById(anyString())).thenReturn(Optional.empty());
    when(globalSettingRepository.existsById(LlmGlobalSettingEntity.SINGLETON_ID)).thenReturn(true);
    when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(Optional.of(setting));
  }

  private LlmGlobalSettingEntity setting(String chat, String embedding, String vision) {
    return LlmGlobalSettingEntity.builder()
        .id(LlmGlobalSettingEntity.SINGLETON_ID)
        .defaultChatProviderId(chat)
        .defaultEmbeddingProviderId(embedding)
        .defaultVisionProviderId(vision)
        .build();
  }

  private LlmProviderEntity providerEntity(String id, String nonce, String ciphertext) {
    return LlmProviderEntity.builder()
        .id(id)
        .baseUrl("https://example.com/v1")
        .apiKeyNonce(nonce)
        .apiKeyCiphertext(ciphertext)
        .model("test-model")
        .supportsVision(id.contains("-vl-"))
        .enabled(true)
        .builtin(true)
        .build();
  }

  private ProviderConfig provider(String baseUrl, String model) {
    ProviderConfig config = new ProviderConfig();
    config.setBaseUrl(baseUrl);
    config.setModel(model);
    config.setSupportsVision(false);
    config.setSupportsEmbedding(false);
    return config;
  }

  private ProviderConfig visionProvider(String baseUrl, String model) {
    ProviderConfig config = provider(baseUrl, model);
    config.setSupportsVision(true);
    return config;
  }

  private ProviderConfig embeddingProvider() {
    ProviderConfig config = provider("https://api.siliconflow.cn/v1", "Qwen/Qwen3-8B");
    config.setEmbeddingModel("BAAI/bge-m3");
    config.setEmbeddingDimensions(1024);
    config.setSupportsEmbedding(true);
    return config;
  }
}
