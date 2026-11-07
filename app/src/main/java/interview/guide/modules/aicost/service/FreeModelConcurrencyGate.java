package interview.guide.modules.aicost.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 免费模型的统一调用闸门。
 *
 * <p>是否免费以价格表为准：同一 Provider / 模型 / 模态存在当前价格且输入、输出、缓存价格
 * 全部为 0 时视为免费。价格表没有记录时，回退到 {@code app.ai.free-models} 中声明的
 * 文本、识图、Embedding、音频 Provider，避免种子价格尚未同步时漏掉限制。</p>
 */
@Service
@RequiredArgsConstructor
public class FreeModelConcurrencyGate {

  public static final String TEXT = "TEXT";
  public static final String VISION = "VISION";
  public static final String EMBEDDING = "EMBEDDING";
  public static final String AUDIO = "AUDIO";

  private final FreeModelConcurrencyProperties properties;
  private final AiCostCalculator costCalculator;
  private final LlmProviderProperties providerProperties;

  private final Map<PermitKey, Semaphore> permits = new ConcurrentHashMap<>();

  @PostConstruct
  void initPermits() {
    permits.clear();
  }

  public boolean isFree(String providerId, String model, String modality) {
    if (!properties.isEnabled()
        || !StringUtils.hasText(providerId)
        || !StringUtils.hasText(model)
        || !StringUtils.hasText(modality)) {
      return false;
    }
    String normalizedModality = normalizeModality(modality);
    return costCalculator.freeStatus(providerId.trim(), model.trim(), normalizedModality)
        .orElseGet(() -> configuredFree(providerId.trim(), normalizedModality));
  }

  public boolean isCurrentTextModelFree(LlmProviderRegistry registry) {
    if (!properties.isEnabled() || registry == null) {
      return false;
    }
    return isFree(
        registry.getCurrentUserChatProviderId(),
        registry.getCurrentUserChatModelName(),
        TEXT);
  }

  /**
   * 免费模型调用前获取全局许可；付费模型返回无操作许可，不改变原并发。
   */
  public Permit acquireIfFree(String providerId, String model, String modality) {
    if (!isFree(providerId, model, modality)) {
      return Permit.noop();
    }
    String normalizedModality = normalizeModality(modality);
    String normalizedProvider = providerId.trim();
    Semaphore semaphore = permitsFor(normalizedProvider, normalizedModality);
    if (semaphore == null) {
      return Permit.noop();
    }
    try {
      semaphore.acquire();
      return new Permit(semaphore);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.AI_RATE_LIMIT_EXCEEDED,
          "等待免费模型调用许可被中断，请稍后重试",
          e);
    }
  }

  public int getAnalysisBatchSize() {
    return properties.getAnalysisBatchSize();
  }

  private boolean configuredFree(String providerId, String modality) {
    LlmProviderProperties.FreeModels free = providerProperties.getFreeModels();
    if (free == null) {
      return false;
    }
    return switch (modality) {
      case TEXT -> sameProvider(providerId, free.getTextProvider());
      case VISION -> sameProvider(providerId, free.getVisionProvider());
      case EMBEDDING -> sameProvider(providerId, free.getEmbeddingProvider());
      case AUDIO -> sameProvider(providerId, free.getAudioProvider());
      default -> false;
    };
  }

  private boolean sameProvider(String current, String configured) {
    return StringUtils.hasText(current)
        && StringUtils.hasText(configured)
        && configured.trim().equals(current.trim());
  }

  private Semaphore permitsFor(String providerId, String modality) {
    int permitCount = configuredPermits(providerId, modality);
    if (permitCount <= 0) {
      return null;
    }
    return permits.computeIfAbsent(
        new PermitKey(providerId, modality),
        key -> new Semaphore(permitCount, true));
  }

  private int configuredPermits(String providerId, String modality) {
    int defaults = defaultPermits(modality);
    Map<String, FreeModelConcurrencyProperties.ProviderPermits> overrides =
        properties.getProviderPermits();
    FreeModelConcurrencyProperties.ProviderPermits override =
        overrides == null ? null : overrides.get(providerId);
    if (override == null) {
      return defaults;
    }
    Integer configured = switch (modality) {
      case TEXT -> override.getTextPermits();
      case VISION -> override.getVisionPermits();
      case EMBEDDING -> override.getEmbeddingPermits();
      case AUDIO -> override.getAudioPermits();
      default -> null;
    };
    return configured == null || configured <= 0 ? defaults : configured;
  }

  private int defaultPermits(String modality) {
    return switch (modality) {
      case TEXT -> Math.max(1, properties.getTextPermits());
      case VISION -> Math.max(1, properties.getVisionPermits());
      case EMBEDDING -> Math.max(1, properties.getEmbeddingPermits());
      case AUDIO -> Math.max(1, properties.getAudioPermits());
      default -> 0;
    };
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
