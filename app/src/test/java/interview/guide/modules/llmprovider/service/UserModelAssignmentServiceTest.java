package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.AuthUserPrincipal;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelAssignmentRequest;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelBatchAssignRequest;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelPageResult;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.model.UserModelOverrideEntity;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.repository.UserModelOverrideRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("用户模型绑定：单个/批量赋值与清除")
class UserModelAssignmentServiceTest {

  @Mock
  private UserModelOverrideRepository overrideRepository;
  @Mock
  private LlmProviderRepository providerRepository;
  @Mock
  private UserRepository userRepository;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;

  private UserModelAssignmentService service;

  @BeforeEach
  void setUp() {
    service = new UserModelAssignmentService(
        overrideRepository, providerRepository, userRepository, llmProviderRegistry);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(
            new AuthUserPrincipal(999L, "admin", "管理员", Set.of("ROLE_ADMIN"), Set.of()),
            null));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("单个用户同时设置文字和识图模型")
  void assignsTextAndVision() {
    when(userRepository.existsById(7L)).thenReturn(true);
    when(providerRepository.findById("p-text")).thenReturn(Optional.of(provider("p-text", false)));
    when(providerRepository.findById("p-vision")).thenReturn(Optional.of(provider("p-vision", true)));
    when(overrideRepository.findByUserIdAndModality(7L, UserModelOverrideEntity.TEXT))
        .thenReturn(Optional.empty());
    when(overrideRepository.findByUserIdAndModality(7L, UserModelOverrideEntity.VISION))
        .thenReturn(Optional.empty());

    service.assign(7L, new UserModelAssignmentRequest("p-text", "p-vision"));

    ArgumentCaptor<UserModelOverrideEntity> captor =
        ArgumentCaptor.forClass(UserModelOverrideEntity.class);
    verify(overrideRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(UserModelOverrideEntity::getModality, UserModelOverrideEntity::getProviderId,
            UserModelOverrideEntity::getUpdatedBy)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("TEXT", "p-text", 999L),
            org.assertj.core.groups.Tuple.tuple("VISION", "p-vision", 999L));
  }

  @Test
  @DisplayName("传空值表示清除该维度绑定")
  void blankValueClearsOverride() {
    when(userRepository.existsById(7L)).thenReturn(true);

    service.assign(7L, new UserModelAssignmentRequest(null, " "));

    verify(overrideRepository).deleteByUserIdAndModality(7L, UserModelOverrideEntity.TEXT);
    verify(overrideRepository).deleteByUserIdAndModality(7L, UserModelOverrideEntity.VISION);
    verify(overrideRepository, never()).save(any());
  }

  @Test
  @DisplayName("识图绑定到非视觉 Provider 会被拒绝")
  void rejectsNonVisionProviderForVision() {
    when(userRepository.existsById(7L)).thenReturn(true);
    when(providerRepository.findById("p-text")).thenReturn(Optional.of(provider("p-text", false)));

    assertThatThrownBy(() ->
        service.assign(7L, new UserModelAssignmentRequest(null, "p-text")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未标记为支持图片输入");
  }

  @Test
  @DisplayName("批量赋值含不存在用户时整体失败")
  void batchRejectsUnknownUser() {
    when(userRepository.existsById(7L)).thenReturn(true);
    when(userRepository.existsById(8L)).thenReturn(false);

    assertThatThrownBy(() ->
        service.assignBatch(new UserModelBatchAssignRequest(List.of(7L, 8L), "p-text", null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("用户 8 不存在");

    verify(overrideRepository, never()).save(any());
  }

  @Test
  @DisplayName("批量赋值对所有选中用户写入同一份绑定")
  void batchAppliesToAllUsers() {
    when(userRepository.existsById(7L)).thenReturn(true);
    when(userRepository.existsById(8L)).thenReturn(true);
    when(providerRepository.findById("p-text")).thenReturn(Optional.of(provider("p-text", false)));
    when(overrideRepository.findByUserIdAndModality(any(), eq(UserModelOverrideEntity.TEXT)))
        .thenReturn(Optional.empty());

    service.assignBatch(new UserModelBatchAssignRequest(List.of(7L, 8L), "p-text", null));

    ArgumentCaptor<UserModelOverrideEntity> captor =
        ArgumentCaptor.forClass(UserModelOverrideEntity.class);
    verify(overrideRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(UserModelOverrideEntity::getUserId)
        .containsExactlyInAnyOrder(7L, 8L);
  }

  @Test
  @DisplayName("用户列表返回绑定值和继承状态")
  void listUsersReturnsAssignments() {
    UserEntity user = new UserEntity();
    user.setId(7L);
    user.setUsername("alice");
    user.setDisplayName("Alice");
    when(userRepository.findAll(any(Pageable.class))).thenReturn(
        new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
    UserModelOverrideEntity text = UserModelOverrideEntity.builder()
        .userId(7L)
        .modality(UserModelOverrideEntity.TEXT)
        .providerId("p-text")
        .build();
    when(overrideRepository.findAllByUserIdIn(List.of(7L))).thenReturn(List.of(text));
    when(llmProviderRegistry.resolveChatProviderIdForUser(7L)).thenReturn("p-text");
    when(llmProviderRegistry.resolveVisionProviderIdForUser(7L)).thenReturn("p-vision");
    when(providerRepository.findById("p-text"))
        .thenReturn(Optional.of(provider("p-text", "model-p-text", false)));
    when(providerRepository.findById("p-vision"))
        .thenReturn(Optional.of(provider("p-vision", "model-p-vision", true)));

    UserModelPageResult result = service.listUsers(null, 0, 20);

    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).textProviderId()).isEqualTo("p-text");
    assertThat(result.items().get(0).visionProviderId()).isNull();
    assertThat(result.items().get(0).textEffectiveProviderId()).isEqualTo("p-text");
    assertThat(result.items().get(0).textEffectiveModel()).isEqualTo("model-p-text");
    assertThat(result.items().get(0).textDefault()).isFalse();
    assertThat(result.items().get(0).visionEffectiveProviderId()).isEqualTo("p-vision");
    assertThat(result.items().get(0).visionEffectiveModel()).isEqualTo("model-p-vision");
    assertThat(result.items().get(0).visionDefault()).isTrue();
  }

  @Test
  @DisplayName("默认路由也返回实际生效模型名")
  void listUsersReturnsEffectiveDefaultModels() {
    UserEntity user = new UserEntity();
    user.setId(7L);
    user.setUsername("alice");
    user.setDisplayName("Alice");
    when(userRepository.findAll(any(Pageable.class))).thenReturn(
        new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
    when(overrideRepository.findAllByUserIdIn(List.of(7L))).thenReturn(List.of());
    when(llmProviderRegistry.resolveChatProviderIdForUser(7L)).thenReturn("dashscope-3.7");
    when(llmProviderRegistry.resolveVisionProviderIdForUser(7L))
        .thenReturn("dashscope-3.7-vl");
    when(providerRepository.findById("dashscope-3.7"))
        .thenReturn(Optional.of(provider(
            "dashscope-3.7", "qwen3.7-flash", false)));
    when(providerRepository.findById("dashscope-3.7-vl"))
        .thenReturn(Optional.of(provider(
            "dashscope-3.7-vl", "qwen3.7-flash", true)));

    UserModelPageResult result = service.listUsers(null, 0, 20);

    assertThat(result.items().get(0).textProviderId()).isNull();
    assertThat(result.items().get(0).textEffectiveProviderId()).isEqualTo("dashscope-3.7");
    assertThat(result.items().get(0).textEffectiveModel()).isEqualTo("qwen3.7-flash");
    assertThat(result.items().get(0).textDefault()).isTrue();
    assertThat(result.items().get(0).visionProviderId()).isNull();
    assertThat(result.items().get(0).visionEffectiveProviderId()).isEqualTo("dashscope-3.7-vl");
    assertThat(result.items().get(0).visionEffectiveModel()).isEqualTo("qwen3.7-flash");
    assertThat(result.items().get(0).visionDefault()).isTrue();
  }

  private LlmProviderEntity provider(String id, boolean supportsVision) {
    return provider(id, "model-" + id, supportsVision);
  }

  private LlmProviderEntity provider(String id, String model, boolean supportsVision) {
    return LlmProviderEntity.builder()
        .id(id)
        .model(model)
        .enabled(true)
        .supportsVision(supportsVision)
        .build();
  }
}
