package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import interview.guide.modules.auth.model.RoleCode;
import interview.guide.modules.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 调用管理员豁免判定")
class AiAdminPolicyTest {

  private static final Long USER_ID = 7L;

  @Mock
  private UserRepository userRepository;

  @Test
  @DisplayName("管理员账号命中管理员角色")
  void shouldDetectAdmin() {
    when(userRepository.countByIdAndRoleCode(USER_ID, RoleCode.ADMIN.code())).thenReturn(1L);

    assertThat(new AiAdminPolicy(userRepository).isAdmin(USER_ID)).isTrue();
  }

  @Test
  @DisplayName("普通账号不享受豁免")
  void shouldNotDetectNormalUserAsAdmin() {
    when(userRepository.countByIdAndRoleCode(USER_ID, RoleCode.ADMIN.code())).thenReturn(0L);

    assertThat(new AiAdminPolicy(userRepository).isAdmin(USER_ID)).isFalse();
  }

  @Test
  @DisplayName("同一用户短时间复用角色判定结果")
  void shouldCacheRoleDecision() {
    when(userRepository.countByIdAndRoleCode(USER_ID, RoleCode.ADMIN.code())).thenReturn(1L);
    AiAdminPolicy policy = new AiAdminPolicy(userRepository);

    assertThat(policy.isAdmin(USER_ID)).isTrue();
    assertThat(policy.isAdmin(USER_ID)).isTrue();

    verify(userRepository).countByIdAndRoleCode(USER_ID, RoleCode.ADMIN.code());
  }

  @Test
  @DisplayName("系统调用没有用户 id 时不查数据库")
  void shouldTreatNullUserAsSystem() {
    assertThat(new AiAdminPolicy(userRepository).isAdmin(null)).isFalse();

    verifyNoInteractions(userRepository);
  }
}
