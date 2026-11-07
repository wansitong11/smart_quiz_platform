package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.aicost.model.AiUserRateLimitOverrideEntity;
import interview.guide.modules.aicost.repository.AiUserRateLimitOverrideRepository;
import interview.guide.modules.aicost.AiCostProperties;
import interview.guide.modules.auth.model.RoleCode;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("用户 AI 配额管理")
class AiUserLimitAdminServiceTest {

  private static final Long USER_ID = 7L;

  @Mock
  private UserRepository userRepository;

  @Mock
  private AiAdminPolicy adminPolicy;

  @Mock
  private AiUserRateLimitOverrideRepository rateOverrideRepository;

  @Mock
  private AiUserRateLimitResolver rateLimitResolver;

  @Mock
  private UserAccessPolicy accessPolicy;

  @Mock
  private AiQuotaConfigService quotaConfigService;

  @Test
  @DisplayName("清除功能级限流时不关闭用户全局限流覆盖")
  void shouldNotCloseGlobalRateOverrideWhenClearingFeature() {
    when(userRepository.existsById(USER_ID)).thenReturn(true);
    when(adminPolicy.isAdmin(USER_ID)).thenReturn(false);
    AiUserRateLimitOverrideEntity featureRate = AiUserRateLimitOverrideEntity.builder()
        .userId(USER_ID)
        .feature("PAPER_IMPORT")
        .rpmLimit(3)
        .effectiveFrom(Instant.now())
        .build();
    when(rateOverrideRepository.findActiveFeature(
        eq(USER_ID), eq("PAPER_IMPORT"), any(Instant.class)))
        .thenReturn(List.of(featureRate));

    AiUserLimitAdminService service = new AiUserLimitAdminService(
        userRepository, adminPolicy, rateOverrideRepository, rateLimitResolver,
        new AiCostProperties(), accessPolicy, quotaConfigService);
    service.clear(USER_ID, "PAPER_IMPORT");

    verify(rateOverrideRepository, never()).findActiveGlobal(eq(USER_ID), any(Instant.class));
    verify(rateLimitResolver).invalidate(USER_ID);
    assertThat(featureRate.getEffectiveTo()).isNotNull();
  }

  @Test
  @DisplayName("可配置用户列表排除管理员")
  void shouldExcludeAdminUsersFromQuotaUserList() {
    UserEntity normalUser = user(2L, "stonee", RoleCode.USER);
    UserEntity admin = user(1L, "admin", RoleCode.ADMIN);
    when(userRepository.findAllWithRoles()).thenReturn(List.of(admin, normalUser));

    AiUserLimitAdminService service = new AiUserLimitAdminService(
        userRepository, adminPolicy, rateOverrideRepository, rateLimitResolver,
        new AiCostProperties(), accessPolicy, quotaConfigService);

    assertThat(service.users())
        .extracting(item -> item.id())
        .containsExactly(2L);
    assertThat(service.users())
        .extracting(item -> item.roles())
        .containsExactly(List.of(RoleCode.USER.code()));
  }

  @Test
  @DisplayName("未配置用户配额时返回系统默认生效值")
  void shouldReturnEffectiveDefaultsWhenNoOverride() {
    when(userRepository.existsById(USER_ID)).thenReturn(true);
    when(adminPolicy.isAdmin(USER_ID)).thenReturn(false);
    when(rateOverrideRepository.findActiveByUser(eq(USER_ID), any(Instant.class)))
        .thenReturn(List.of());

    AiUserLimitAdminService service = new AiUserLimitAdminService(
        userRepository, adminPolicy, rateOverrideRepository, rateLimitResolver,
        new AiCostProperties(), accessPolicy, quotaConfigService);

    var global = service.get(USER_ID).stream()
        .filter(item -> item.feature() == null)
        .findFirst()
        .orElseThrow();

    assertThat(global.effective().rpmLimit()).isEqualTo(10);
    assertThat(global.effective().tpmLimit()).isEqualTo(200_000L);
    assertThat(global.effective().concurrencyLimit()).isEqualTo(4);
  }

  @Test
  @DisplayName("修改档位后立即失效档位与人均额度缓存")
  void shouldInvalidateTierAndQuotaCachesAfterTierChange() {
    UserEntity user = user(USER_ID, "stonee", RoleCode.USER);
    when(userRepository.existsById(USER_ID)).thenReturn(true);
    when(adminPolicy.isAdmin(USER_ID)).thenReturn(false);
    when(userRepository.findById(USER_ID)).thenReturn(java.util.Optional.of(user));

    AiUserLimitAdminService service = new AiUserLimitAdminService(
        userRepository, adminPolicy, rateOverrideRepository, rateLimitResolver,
        new AiCostProperties(), accessPolicy, quotaConfigService);
    service.updateTier(USER_ID, "AUTHORIZED", 1L);

    assertThat(user.getTier().name()).isEqualTo("AUTHORIZED");
    verify(accessPolicy).invalidate(USER_ID);
    verify(quotaConfigService).invalidate();
  }

  private UserEntity user(Long id, String username, RoleCode roleCode) {
    RoleEntity role = new RoleEntity();
    role.setCode(roleCode.code());
    role.setName(roleCode.roleName());
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setUsername(username);
    user.setDisplayName(username);
    user.setStatus(UserStatus.ACTIVE);
    user.setRoles(Set.of(role));
    return user;
  }
}
