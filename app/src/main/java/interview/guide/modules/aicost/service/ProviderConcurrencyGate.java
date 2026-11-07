package interview.guide.modules.aicost.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 按 Provider + 模态隔离的并发闸门。
 *
 * <p>只限制配置中显式列出的 Provider；未配置的 Provider 返回无操作许可。
 * 与免费模型闸门独立：免费模型可以同时受“全局免费额度”和“Provider 额度”保护，
 * 付费模型则只受这里配置的 Provider 额度保护。</p>
 */
@Service
@RequiredArgsConstructor
public class ProviderConcurrencyGate {

  private final ProviderConcurrencyProperties properties;
  private final Map<PermitKey, Semaphore> permits = new ConcurrentHashMap<>();

  public boolean isConfigured(String providerId, String modality) {
    return configuredPermits(providerId, modality) > 0;
  }

  public Permit acquire(String providerId, String modality) {
    int permitCount = configuredPermits(providerId, modality);
    if (!properties.isEnabled() || permitCount <= 0) {
      return Permit.noop();
    }
    Semaphore semaphore = permits.computeIfAbsent(
        new PermitKey(providerId.trim(), normalizeModality(modality)),
        key -> new Semaphore(permitCount, true));
    try {
      semaphore.acquire();
      return new Permit(semaphore);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.AI_RATE_LIMIT_EXCEEDED,
          "等待模型调用许可被中断，请稍后重试",
          e);
    }
  }

  private int configuredPermits(String providerId, String modality) {
    if (!properties.isEnabled() || !StringUtils.hasText(providerId)) {
      return 0;
    }
    ProviderConcurrencyProperties.ProviderLimits limits =
        properties.getProviders().get(providerId.trim());
    if (limits == null) {
      return 0;
    }
    Integer configured = switch (normalizeModality(modality)) {
      case FreeModelConcurrencyGate.TEXT -> limits.getTextPermits();
      case FreeModelConcurrencyGate.VISION -> limits.getVisionPermits();
      case FreeModelConcurrencyGate.EMBEDDING -> limits.getEmbeddingPermits();
      case FreeModelConcurrencyGate.AUDIO -> limits.getAudioPermits();
      default -> null;
    };
    return configured == null ? 0 : configured;
  }

  private String normalizeModality(String modality) {
    return modality == null ? "" : modality.trim().toUpperCase(Locale.ROOT);
  }

  private record PermitKey(String providerId, String modality) {
  }

  public static final class Permit implements AutoCloseable {

    private static final Permit NOOP = new Permit(null);

    private final Semaphore semaphore;
    private final AtomicBoolean released = new AtomicBoolean(false);

    private Permit(Semaphore semaphore) {
      this.semaphore = semaphore;
    }

    public static Permit noop() {
      return NOOP;
    }

    @Override
    public void close() {
      if (semaphore != null && released.compareAndSet(false, true)) {
        semaphore.release();
      }
    }
  }
}
