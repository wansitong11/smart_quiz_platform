package interview.guide.modules.aicost.service;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiCallMeter;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.ai.AiRateLimitThrottle;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.aicost.AiCallSource;
import interview.guide.modules.aicost.AiCallStatus;
import interview.guide.modules.aicost.AiCostProperties;
import interview.guide.modules.aicost.AiModality;
import interview.guide.modules.aicost.model.AiCallEventEntity;
import interview.guide.modules.aicost.service.AiCostCalculator.CostResult;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 统一计量与闸门：挂载在 {@code LlmProviderRegistry} 创建的 ChatClient / EmbeddingModel 上。
 * 同步请求由 {@code AiCostAspect} 写入上下文；异步上下文由 Stream 透传后回填。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCallRecorder implements AiCallMeter {

  private static final long DEFAULT_ESTIMATE_TOKENS = 1000;
  private static final int CHARS_PER_TOKEN = 4;

  private final AiCostProperties properties;
  private final AiUsageGate usageGate;
  private final AiCostCalculator costCalculator;
  private final AiLedgerWriter ledgerWriter;
  private final AiBudgetService budgetService;
  private final FreeModelConcurrencyGate freeModelConcurrencyGate;
  private final ProviderConcurrencyGate providerConcurrencyGate;
  private final AiRateLimitThrottle rateLimitThrottle;

  @Override
  public Advisor chatAdvisor(String providerId, String model, String modality) {
    return new AiCallAdvisor(providerId, model, modality);
  }

  @Override
  public EmbeddingModel wrapEmbedding(EmbeddingModel delegate, String providerId, String model) {
    return new EmbeddingModel() {
      @Override
      public EmbeddingResponse call(EmbeddingRequest request) {
        AiCallContext context = AiCallContextHolder.get();
        boolean acquired = acquire(context);
        try (ProviderConcurrencyGate.Permit providerPermit =
                 providerConcurrencyGate.acquire(
                     providerId, AiModality.EMBEDDING.name());
             FreeModelConcurrencyGate.Permit freePermit =
                 freeModelConcurrencyGate.acquireIfFree(
                     providerId, model, AiModality.EMBEDDING.name())) {
          long start = System.nanoTime();
          try {
            EmbeddingResponse response = delegate.call(request);
            long chars = request.getInstructions() == null ? 0
                : request.getInstructions().stream()
                    .mapToLong(s -> s == null ? 0 : s.length()).sum();
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            record(context, providerId, model, AiModality.EMBEDDING.name(), AiCallStatus.SUCCESS,
                null, start, usage, chars, 0);
            return response;
          } catch (Exception e) {
            recordFailure(context, providerId, model, AiModality.EMBEDDING.name(), start, e);
            throw e;
          }
        } finally {
          if (acquired) {
            release(context);
          }
        }
      }

      @Override
      public float[] embed(Document document) {
        AiCallContext context = AiCallContextHolder.get();
        boolean acquired = acquire(context);
        try (ProviderConcurrencyGate.Permit providerPermit =
                 providerConcurrencyGate.acquire(
                     providerId, AiModality.EMBEDDING.name());
             FreeModelConcurrencyGate.Permit freePermit =
                 freeModelConcurrencyGate.acquireIfFree(
                     providerId, model, AiModality.EMBEDDING.name())) {
          long start = System.nanoTime();
          try {
            float[] embedding = delegate.embed(document);
            long chars = document == null || document.getText() == null
                ? 0 : document.getText().length();
            record(context, providerId, model, AiModality.EMBEDDING.name(),
                AiCallStatus.SUCCESS, null, start, null, chars, 0);
            return embedding;
          } catch (Exception e) {
            recordFailure(context, providerId, model, AiModality.EMBEDDING.name(), start, e);
            throw e;
          }
        } finally {
          if (acquired) {
            release(context);
          }
        }
      }
    };
  }

  private final class AiCallAdvisor implements CallAdvisor, StreamAdvisor {

    private final String providerId;
    private final String model;
    private final String modality;

    private AiCallAdvisor(String providerId, String model, String modality) {
      this.providerId = providerId;
      this.model = model;
      this.modality = modality;
    }

    @Override
    public String getName() {
      return "ai-cost-recorder";
    }

    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public ChatClientResponse adviseCall(
        ChatClientRequest request,
        CallAdvisorChain chain) {
      AiCallContext context = AiCallContextHolder.get();
      boolean free = freeModelConcurrencyGate.isFree(providerId, model, modality);
      boolean throttle = free || providerConcurrencyGate.isConfigured(providerId, modality);
      boolean acquired = false;
      try {
        acquired = acquire(context);
        try (ProviderConcurrencyGate.Permit providerPermit =
                 providerConcurrencyGate.acquire(providerId, modality);
             FreeModelConcurrencyGate.Permit freePermit =
                 freeModelConcurrencyGate.acquireIfFree(providerId, model, modality)) {
          if (throttle) {
            rateLimitThrottle.awaitOpen(providerId, model, modality);
          }
          long start = System.nanoTime();
          try {
            ChatClientResponse response = chain.nextCall(request);
            recordSuccess(context, providerId, model, modality, start, response);
            return response;
          } catch (Exception e) {
            if (throttle) {
              rateLimitThrottle.noteFailure(providerId, model, modality, e);
            }
            recordFailure(context, providerId, model, modality, start, e);
            throw e;
          }
        }
      } finally {
        if (acquired) {
          release(context);
        }
      }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
        ChatClientRequest request,
        StreamAdvisorChain chain) {
      AiCallContext context = AiCallContextHolder.get();
      boolean free = freeModelConcurrencyGate.isFree(providerId, model, modality);
      boolean throttle = free || providerConcurrencyGate.isConfigured(providerId, modality);
      return Flux.defer(() -> {
            boolean acquired = false;
            ProviderConcurrencyGate.Permit providerPermit = null;
            FreeModelConcurrencyGate.Permit freePermit = null;
            try {
              acquired = acquire(context);
              providerPermit = providerConcurrencyGate.acquire(providerId, modality);
              freePermit = freeModelConcurrencyGate.acquireIfFree(providerId, model, modality);
              if (throttle) {
                rateLimitThrottle.awaitOpen(providerId, model, modality);
              }
              long start = System.nanoTime();
              AtomicReference<String> actualModel = new AtomicReference<>(model);
              AtomicReference<Usage> usage = new AtomicReference<>();
              ProviderConcurrencyGate.Permit finalProviderPermit = providerPermit;
              FreeModelConcurrencyGate.Permit finalFreePermit = freePermit;
              boolean finalAcquired = acquired;
              return chain.nextStream(request)
                  .doOnNext(response -> captureUsage(response, actualModel, usage))
                  .doOnComplete(() -> record(context, providerId, actualModel.get(), modality,
                      AiCallStatus.SUCCESS, null, start, usage.get(), 0, 0))
                  .doOnError(error -> {
                    if (throttle) {
                      rateLimitThrottle.noteFailure(providerId, model, modality, error);
                    }
                    recordFailure(context, providerId, actualModel.get(), modality, start, error);
                  })
                  .doFinally(signal -> {
                    finalFreePermit.close();
                    finalProviderPermit.close();
                    if (finalAcquired) {
                      release(context);
                    }
                  });
            } catch (RuntimeException e) {
              if (freePermit != null) {
                freePermit.close();
              }
              if (providerPermit != null) {
                providerPermit.close();
              }
              if (acquired) {
                release(context);
              }
              throw e;
            }
          })
          .subscribeOn(Schedulers.boundedElastic());
    }

    private void captureUsage(
        ChatClientResponse response,
        AtomicReference<String> actualModel,
        AtomicReference<Usage> usage) {
      if (response == null || response.chatResponse() == null
          || response.chatResponse().getMetadata() == null) {
        return;
      }
      ChatResponse chatResponse = response.chatResponse();
      if (chatResponse.getMetadata().getModel() != null) {
        actualModel.set(chatResponse.getMetadata().getModel());
      }
      if (chatResponse.getMetadata().getUsage() != null) {
        usage.set(chatResponse.getMetadata().getUsage());
      }
    }
  }

  private boolean acquire(AiCallContext context) {
    if (!properties.isEnabled() || context == null || context.feature() == null
        || context.userId() == null) {
      return false;
    }
    budgetService.ensureReserved(context.operationId(), context.userId(), context.feature());
    return usageGate.tryAcquire(context.feature(), context.userId(), DEFAULT_ESTIMATE_TOKENS);
  }

  private void release(AiCallContext context) {
    usageGate.release(context.feature(), context.userId());
  }

  private void recordSuccess(
      AiCallContext context,
      String providerId,
      String model,
      String modality,
      long start,
      ChatClientResponse response
  ) {
    ChatResponse chatResponse = response.chatResponse();
    String actualModel = model;
    Usage usage = null;
    if (chatResponse != null && chatResponse.getMetadata() != null) {
      actualModel = chatResponse.getMetadata().getModel() != null
          ? chatResponse.getMetadata().getModel() : model;
      usage = chatResponse.getMetadata().getUsage();
    }
    record(context, providerId, actualModel, modality, AiCallStatus.SUCCESS,
        null, start, usage, 0, 0);
  }

  private void recordFailure(
      AiCallContext context,
      String providerId,
      String model,
      String modality,
      long start,
      Throwable error
  ) {
    AiCallStatus status = classify(error);
    record(context, providerId, model, modality, status,
        error == null ? null : error.getClass().getSimpleName(), start, null, 0, 0);
  }

  private AiCallStatus classify(Throwable error) {
    if (error instanceof BusinessException business
        && business.getCode() != null
        && business.getCode() == interview.guide.common.exception.ErrorCode.AI_RATE_LIMIT_EXCEEDED.getCode()) {
      return AiCallStatus.RATE_LIMITED;
    }
    if (interview.guide.common.ai.StructuredOutputInvoker.isTransportFailure(error)) {
      return AiCallStatus.TIMEOUT;
    }
    return AiCallStatus.PROVIDER_ERROR;
  }

  private void record(
      AiCallContext context,
      String provider,
      String model,
      String modality,
      AiCallStatus status,
      String errorClass,
      long start,
      Usage usage,
      long charCount,
      int imageCount
  ) {
    if (!properties.isEnabled()) {
      return;
    }
    long latencyMs = (System.nanoTime() - start) / 1_000_000;
    long inputTokens = usage == null ? estimateTokens(charCount) : nvl(usage.getPromptTokens());
    long outputTokens = usage == null ? 0 : nvl(usage.getCompletionTokens());
    long cachedTokens = usage == null ? 0
        : nvl(usage.getCacheReadInputTokens()) + nvl(usage.getCacheWriteInputTokens());

    CostResult cost = costCalculator.estimate(
        provider, model, modality, inputTokens, outputTokens, cachedTokens);

    AiCallEventEntity event = AiCallEventEntity.builder()
        .requestId(context == null ? UUID.randomUUID().toString()
            : context.requestId() != null ? context.requestId() : UUID.randomUUID().toString())
        .operationId(context != null && context.operationId() != null
            ? context.operationId() : "op-" + UUID.randomUUID())
        .tenantId("default")
        .userId(context == null ? null : context.userId())
        .feature(context == null || context.feature() == null
            ? AiFeature.UNKNOWN.name() : context.feature().name())
        .operation(context == null || context.operation() == null
            ? "" : context.operation())
        .source(resolveSource(context))
        .provider(provider)
        .model(model)
        .modality(modality)
        .status(status.name())
        .errorClass(errorClass)
        .retryIndex(0)
        .latencyMs(latencyMs)
        .inputTokens(inputTokens)
        .outputTokens(outputTokens)
        .cachedTokens(cachedTokens)
        .imageCount(imageCount)
        .charCount(charCount > 0 ? charCount : null)
        .estimatedCost(cost.amount())
        .currency(cost.currency())
        .priceVersion(cost.priceVersion())
        .estimated(cost.estimated())
        .occurredAt(java.time.Instant.now())
        .build();
    ledgerWriter.record(event);
    if (context != null && context.operationId() != null) {
      budgetService.recordModelCall(context.operationId(),
          cost.amount() == null ? 0 : toMicro(cost.amount()));
    }
  }

  private static long toMicro(java.math.BigDecimal amount) {
    return amount.multiply(java.math.BigDecimal.valueOf(1_000_000))
        .setScale(0, java.math.RoundingMode.HALF_UP).longValue();
  }

  private String resolveSource(AiCallContext context) {
    if (context == null || context.source() == null) {
      return AiCallSource.SYSTEM.name();
    }
    return context.source();
  }

  private long estimateTokens(long charCount) {
    if (charCount <= 0) {
      return DEFAULT_ESTIMATE_TOKENS;
    }
    return Math.max(1, charCount / CHARS_PER_TOKEN);
  }

  private long nvl(Integer value) {
    return value == null ? 0 : value;
  }

  private long nvl(Long value) {
    return value == null ? 0 : value;
  }
}
