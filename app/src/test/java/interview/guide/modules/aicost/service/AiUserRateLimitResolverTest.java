package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiFeature;
import interview.guide.modules.aicost.AiCostProperties;
import interview.guide.modules.aicost.model.AiUserRateLimitOverrideEntity;
import interview.guide.modules.aicost.repository.AiUserRateLimitOverrideRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("用户限流覆盖解析")
class AiUserRateLimitResolverTest {

  private static final Long USER_ID = 7L;

  @Mock
  private AiUserRateLimitOverrideRepository overrideRepository;

  @Test
  @DisplayName("未配置时使用系统默认值")
  void shouldFallbackToDefaults() {
    when(overrideRepository.findActiveFeature(eq(USER_ID),
        eq(AiFeature.PAPER_IMPORT.name()), any(Instant.class))).thenReturn(List.of());
    when(overrideRepository.findActiveGlobal(eq(USER_ID), any(Instant.class)))
        .thenReturn(List.of());

    AiUserRateLimitResolver resolver =
        new AiUserRateLimitResolver(new AiCostProperties(), overrideRepository);
    AiUserRateLimitResolver.RateLimits limits =
        resolver.resolve(AiFeature.PAPER_IMPORT, USER_ID);

    assertThat(limits.rpm()).isEqualTo(10);
    assertThat(limits.tpm()).isEqualTo(200_000);
    assertThat(limits.concurrency()).isEqualTo(4);
  }

  @Test
  @DisplayName("功能级覆盖优先于用户全局覆盖")
  void shouldPreferFeatureOverride() {
    when(overrideRepository.findActiveFeature(eq(USER_ID),
        eq(AiFeature.PAPER_IMPORT.name()), any(Instant.class)))
        .thenReturn(List.of(entity(AiFeature.PAPER_IMPORT.name(), 99, 50_000L, 7)));
    when(overrideRepository.findActiveGlobal(eq(USER_ID), any(Instant.class)))
        .thenReturn(List.of(entity(null, 5, null, 2)));

    AiUserRateLimitResolver resolver =
        new AiUserRateLimitResolver(new AiCostProperties(), overrideRepository);
    AiUserRateLimitResolver.RateLimits limits =
        resolver.resolve(AiFeature.PAPER_IMPORT, USER_ID);

    assertThat(limits.rpm()).isEqualTo(99);
    assertThat(limits.tpm()).isEqualTo(50_000);
    assertThat(limits.concurrency()).isEqualTo(7);
  }

  @Test
  @DisplayName("保存配额后清除用户缓存可立即读到新限制")
  void shouldReadNewLimitsAfterInvalidation() {
    when(overrideRepository.findActiveFeature(eq(USER_ID),
        eq(AiFeature.PAPER_IMPORT.name()), any(Instant.class)))
        .thenReturn(List.of(), List.of(entity(AiFeature.PAPER_IMPORT.name(), 33, 30_000L, 3)));
    when(overrideRepository.findActiveGlobal(eq(USER_ID), any(Instant.class)))
        .thenReturn(List.of());

    AiUserRateLimitResolver resolver =
        new AiUserRateLimitResolver(new AiCostProperties(), overrideRepository);
    AiUserRateLimitResolver.RateLimits cached = resolver.resolve(AiFeature.PAPER_IMPORT, USER_ID);
    AiUserRateLimitResolver.RateLimits stillCached =
        resolver.resolve(AiFeature.PAPER_IMPORT, USER_ID);
    resolver.invalidate(USER_ID);
    AiUserRateLimitResolver.RateLimits refreshed =
        resolver.resolve(AiFeature.PAPER_IMPORT, USER_ID);

    assertThat(cached.rpm()).isEqualTo(10);
    assertThat(stillCached.rpm()).isEqualTo(10);
    assertThat(refreshed.rpm()).isEqualTo(33);
    assertThat(refreshed.tpm()).isEqualTo(30_000);
    assertThat(refreshed.concurrency()).isEqualTo(3);
  }

  private AiUserRateLimitOverrideEntity entity(String feature, Integer rpm, Long tpm,
      Integer concurrency) {
    return AiUserRateLimitOverrideEntity.builder()
        .userId(USER_ID)
        .feature(feature)
        .rpmLimit(rpm)
        .tpmLimit(tpm)
        .concurrencyLimit(concurrency)
        .effectiveFrom(Instant.now())
        .build();
  }
}
