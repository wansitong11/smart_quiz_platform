package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiFeature;
import interview.guide.common.ai.AiRateLimitExceededException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aicost.service.AiUserRateLimitResolver.RateLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 调用频次与并发闸门")
class AiUsageGateTest {

  private static final Long USER_ID = 7L;

  @Mock
  private RedissonClient redissonClient;

  @Mock
  private AiAdminPolicy adminPolicy;

  @Mock
  private AiUserRateLimitResolver rateLimitResolver;

  @Mock
  private RScript rScript;

  private AiUsageGate gate;

  @BeforeEach
  void setUp() {
    gate = new AiUsageGate(redissonClient, adminPolicy, rateLimitResolver);
    ReflectionTestUtils.setField(gate, "rScript", rScript);
    ReflectionTestUtils.setField(gate, "gateSha", "gate-sha");
    ReflectionTestUtils.setField(gate, "releaseSha", "release-sha");
  }

  @Test
  @DisplayName("管理员不占用频次与并发槽")
  void shouldBypassGateForAdmin() {
    when(adminPolicy.isAdmin(USER_ID)).thenReturn(true);

    assertThat(gate.tryAcquire(AiFeature.PAPER_IMPORT, USER_ID, 1_000)).isFalse();

    verifyNoInteractions(redissonClient);
  }

  @Test
  @DisplayName("管理员不需要等待 RPM 窗口")
  void shouldNotWaitForAdmin() {
    when(adminPolicy.isAdmin(USER_ID)).thenReturn(true);

    assertThat(gate.remainingRpmWindowMillisIfExhausted(
        AiFeature.PAPER_IMPORT, USER_ID)).isZero();

    verifyNoInteractions(redissonClient);
  }

  @Test
  @DisplayName("普通用户仍按闸门结果放行")
  void shouldAllowNormalUserWhenGatePasses() {
    when(adminPolicy.isAdmin(USER_ID)).thenReturn(false);
    when(rateLimitResolver.resolve(AiFeature.PAPER_IMPORT, USER_ID))
        .thenReturn(new RateLimits(10, 200_000, 4));
    when(rScript.evalSha(
        eq(RScript.Mode.READ_WRITE),
        eq("gate-sha"),
        eq(RScript.ReturnType.VALUE),
        anyList(),
        any(Object[].class))).thenReturn(0L);

    assertThat(gate.tryAcquire(AiFeature.PAPER_IMPORT, USER_ID, 1_000)).isTrue();
  }

  @Test
  @DisplayName("普通用户超限时仍返回限流错误")
  void shouldRejectNormalUserWhenGateExceedsLimit() {
    when(adminPolicy.isAdmin(USER_ID)).thenReturn(false);
    when(rateLimitResolver.resolve(AiFeature.PAPER_IMPORT, USER_ID))
        .thenReturn(new RateLimits(10, 200_000, 4));
    when(rScript.evalSha(
        eq(RScript.Mode.READ_WRITE),
        eq("gate-sha"),
        eq(RScript.ReturnType.VALUE),
        anyList(),
        any(Object[].class))).thenReturn(-1L);

    assertThatThrownBy(() -> gate.tryAcquire(AiFeature.PAPER_IMPORT, USER_ID, 1_000))
        .isInstanceOfSatisfying(AiRateLimitExceededException.class,
            error -> assertThat(error.getCode())
                .isEqualTo(ErrorCode.AI_RATE_LIMIT_EXCEEDED.getCode()));
  }
}
