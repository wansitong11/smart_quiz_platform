package interview.guide.common.ai;

import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.model.UserModelOverrideEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.repository.UserModelOverrideRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Provider 解析：用户绑定优先于全局默认")
class LlmProviderRegistryUserOverrideTest {

  @Mock
  private LlmProviderProperties properties;
  @Mock
  private LlmProviderRepository providerRepository;
  @Mock
  private LlmGlobalSettingRepository globalSettingRepository;
  @Mock
  private UserModelOverrideRepository userModelOverrideRepository;
  @Mock
  private ApiKeyEncryptionService encryptionService;
  @Mock
  private AiUserTierResolver tierResolver;

  private LlmProviderRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new LlmProviderRegistry(properties, providerRepository,
        globalSettingRepository, userModelOverrideRepository, encryptionService,
        null, null, null, null, null);
    registry.setAiUserTierResolver(tierResolver);
    AiCallContextHolder.set(new AiCallContext(
        7L, AiFeature.KB_QA, "test", "req", "op", "SYNC"));
  }

  @AfterEach
  void tearDown() {
    AiCallContextHolder.clear();
  }

  @Test
  @DisplayName("用户有文字绑定时优先于全局默认")
  void userTextOverrideWins() {
    when(userModelOverrideRepository.findByUserIdAndModality(7L, UserModelOverrideEntity.TEXT))
        .thenReturn(Optional.of(override(7L, UserModelOverrideEntity.TEXT, "p-user")));
    when(providerRepository.findById("p-user"))
        .thenReturn(Optional.of(provider("p-user", false, true)));

    String resolved = ReflectionTestUtils.invokeMethod(
        registry, "resolveChatProviderIdForCurrentUser");

    assertThat(resolved).isEqualTo("p-user");
  }

  @Test
  @DisplayName("系统任务没有 userId 时走全局默认")
  void systemTaskUsesGlobalDefault() {
    AiCallContextHolder.clear();
    when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(Optional.of(globalSetting("p-global", "p-vglobal")));

    String resolved = ReflectionTestUtils.invokeMethod(
        registry, "resolveChatProviderIdForCurrentUser");

    assertThat(resolved).isEqualTo("p-global");
  }

  @Test
  @DisplayName("绑定 Provider 已禁用时回退全局默认")
  void disabledOverrideFallsBackToGlobal() {
    when(userModelOverrideRepository.findByUserIdAndModality(7L, UserModelOverrideEntity.TEXT))
        .thenReturn(Optional.of(override(7L, UserModelOverrideEntity.TEXT, "p-disabled")));
    when(providerRepository.findById("p-disabled"))
        .thenReturn(Optional.of(provider("p-disabled", false, false)));
    when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(Optional.of(globalSetting("p-global", "p-vglobal")));

    String resolved = ReflectionTestUtils.invokeMethod(
        registry, "resolveChatProviderIdForCurrentUser");

    assertThat(resolved).isEqualTo("p-global");
  }

  @Test
  @DisplayName("识图绑定优先于全局默认识图")
  void userVisionOverrideWins() {
    when(userModelOverrideRepository.findByUserIdAndModality(7L, UserModelOverrideEntity.VISION))
        .thenReturn(Optional.of(override(7L, UserModelOverrideEntity.VISION, "p-vuser")));
    when(providerRepository.findById("p-vuser"))
        .thenReturn(Optional.of(provider("p-vuser", true, true)));

    String resolved = ReflectionTestUtils.invokeMethod(
        registry, "resolveVisionProviderIdForCurrentUser");

    assertThat(resolved).isEqualTo("p-vuser");
  }

  @Test
  @DisplayName("识图模型名取用户绑定的实际模型")
  void userVisionModelNameUsesOverride() {
    when(userModelOverrideRepository.findByUserIdAndModality(7L, UserModelOverrideEntity.VISION))
        .thenReturn(Optional.of(override(7L, UserModelOverrideEntity.VISION, "p-vuser")));
    when(providerRepository.findById("p-vuser"))
        .thenReturn(Optional.of(provider("p-vuser", true, true)));

    assertThat(registry.getCurrentUserVisionModelName()).isEqualTo("model-p-vuser");
  }

  @Test
  @DisplayName("授权用户的文字模型固定走系统默认，不被用户绑定覆盖")
  void authorizedUserIgnoresTextOverride() {
    when(tierResolver.isAuthorizedTier(7L)).thenReturn(true);
    when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(Optional.of(globalSetting("p-global", "p-vglobal")));

    String resolved = ReflectionTestUtils.invokeMethod(
        registry, "resolveChatProviderIdForCurrentUser");

    assertThat(resolved).isEqualTo("p-global");
  }

  @Test
  @DisplayName("授权用户的识图模型固定走系统默认识图")
  void authorizedUserIgnoresVisionOverride() {
    when(tierResolver.isAuthorizedTier(7L)).thenReturn(true);
    when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(Optional.of(globalSetting("p-global", "p-vglobal")));

    String resolved = ReflectionTestUtils.invokeMethod(
        registry, "resolveVisionProviderIdForCurrentUser");

    assertThat(resolved).isEqualTo("p-vglobal");
  }

  private UserModelOverrideEntity override(Long userId, String modality, String providerId) {
    return UserModelOverrideEntity.builder()
        .userId(userId)
        .modality(modality)
        .providerId(providerId)
        .build();
  }

  private LlmProviderEntity provider(String id, boolean supportsVision, boolean enabled) {
    return LlmProviderEntity.builder()
        .id(id)
        .model("model-" + id)
        .supportsVision(supportsVision)
        .enabled(enabled)
        .build();
  }

  private LlmGlobalSettingEntity globalSetting(String chat, String vision) {
    return LlmGlobalSettingEntity.builder()
        .id(LlmGlobalSettingEntity.SINGLETON_ID)
        .defaultChatProviderId(chat)
        .defaultEmbeddingProviderId("p-embed")
        .defaultVisionProviderId(vision)
        .build();
  }
}
