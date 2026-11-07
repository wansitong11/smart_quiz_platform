package interview.guide.modules.aicost.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aicost.PriceSource;
import interview.guide.modules.aicost.dto.AiUsageDtos.PriceItem;
import interview.guide.modules.aicost.dto.AiUsageDtos.PriceUpsertRequest;
import interview.guide.modules.aicost.model.AiModelPriceEntity;
import interview.guide.modules.aicost.repository.AiModelPriceRepository;
import interview.guide.modules.aicost.service.AiModelPriceSource.PriceSeed;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiModelPriceAdminService {

  private final AiModelPriceRepository priceRepository;
  private final AiModelPriceSource source;
  private final LlmProviderConfigService providerConfigService;

  @Transactional(readOnly = true)
  public List<PriceItem> list() {
    return priceRepository.findActive(Instant.now()).stream()
        .filter(price -> !"AUDIO".equalsIgnoreCase(price.getModality()))
        .map(this::toItem)
        .toList();
  }

  @Transactional
  public PriceItem upsert(PriceUpsertRequest request) {
    if (request.id() != null) {
      return edit(request);
    }
    return create(request);
  }

  private PriceItem edit(PriceUpsertRequest request) {
    AiModelPriceEntity entity = priceRepository.findById(request.id())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "价格记录不存在"));
    if (entity.getSource() == PriceSource.MANUAL) {
      entity.setInputPricePer1m(request.inputPricePer1m());
      entity.setOutputPricePer1m(request.outputPricePer1m());
      entity.setCachedPricePer1m(request.cachedPricePer1m());
      return toItem(priceRepository.save(entity));
    }
    Instant now = Instant.now();
    entity.setEffectiveTo(now);
    priceRepository.save(entity);
    AiModelPriceEntity manual = AiModelPriceEntity.builder()
        .provider(entity.getProvider())
        .model(entity.getModel())
        .modality(entity.getModality())
        .inputPricePer1m(request.inputPricePer1m())
        .outputPricePer1m(request.outputPricePer1m())
        .cachedPricePer1m(request.cachedPricePer1m())
        .unit("TOKEN")
        .currency("CNY")
        .source(PriceSource.MANUAL)
        .effectiveFrom(now)
        .build();
    return toItem(priceRepository.save(manual));
  }

  private PriceItem create(PriceUpsertRequest request) {
    Instant now = Instant.now();
    String provider = requireText(request.provider(), "Provider");
    String model = requireText(request.model(), "Model");
    String modality = request.modality() == null ? "TEXT" : request.modality().toUpperCase();
    if ("AUDIO".equals(modality)) {
      throw new BusinessException(ErrorCode.AI_PRICE_CONFIG_INVALID, "语音模型价格已停用");
    }
    validateProviderSelection(provider, model, modality);
    AiModelPriceEntity sameManual = priceRepository.findActive(now).stream()
        .filter(p -> p.getProvider().equals(provider)
            && p.getModel().equals(model)
            && p.getModality().equals(modality)
            && p.getSource() == PriceSource.MANUAL
            && eq(p.getInputPricePer1m(), request.inputPricePer1m())
            && eq(p.getOutputPricePer1m(), request.outputPricePer1m())
            && eq(p.getCachedPricePer1m(), request.cachedPricePer1m()))
        .findFirst()
        .orElse(null);
    if (sameManual != null) {
      return toItem(sameManual);
    }
    priceRepository.findActive(now).stream()
        .filter(p -> p.getProvider().equals(provider)
            && p.getModel().equals(model)
            && p.getModality().equals(modality))
        .forEach(p -> p.setEffectiveTo(now));
    AiModelPriceEntity entity = AiModelPriceEntity.builder()
        .provider(provider)
        .model(model)
        .modality(modality)
        .inputPricePer1m(request.inputPricePer1m())
        .outputPricePer1m(request.outputPricePer1m())
        .cachedPricePer1m(request.cachedPricePer1m())
        .unit("TOKEN")
        .currency("CNY")
        .source(PriceSource.MANUAL)
        .effectiveFrom(now)
        .build();
    return toItem(priceRepository.save(entity));
  }

  private void validateProviderSelection(String providerId, String model, String modality) {
    ProviderDTO provider = providerConfigService.listProviders().stream()
        .filter(item -> providerId.equals(item.id()))
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            ErrorCode.PROVIDER_NOT_FOUND, "Provider 未配置: " + providerId));
    String expectedModel = switch (modality) {
      case "EMBEDDING" -> provider.embeddingModel();
      case "TEXT", "VISION" -> provider.model();
      default -> null;
    };
    if (expectedModel == null || !expectedModel.equals(model)) {
      throw new BusinessException(ErrorCode.AI_PRICE_CONFIG_INVALID,
          "模型与所选 Provider / 模态不匹配: " + providerId + " / " + model + " / " + modality);
    }
    if ("VISION".equals(modality) && !provider.supportsVision()) {
      throw new BusinessException(ErrorCode.AI_PRICE_CONFIG_INVALID,
          "Provider 未开启识图能力: " + providerId);
    }
    if ("EMBEDDING".equals(modality)
        && (!provider.supportsEmbedding() || provider.embeddingModel() == null)) {
      throw new BusinessException(ErrorCode.AI_PRICE_CONFIG_INVALID,
          "Provider 未配置 Embedding 模型: " + providerId);
    }
  }

  private String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(ErrorCode.AI_PRICE_CONFIG_INVALID, label + " 不能为空");
    }
    return value.trim();
  }

  private boolean eq(java.math.BigDecimal a, java.math.BigDecimal b) {
    if (a == null && b == null) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    return a.compareTo(b) == 0;
  }

  @Transactional
  public void delete(Long id) {
    AiModelPriceEntity entity = priceRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "价格记录不存在"));
    if (entity.getSource() != PriceSource.MANUAL) {
      throw new BusinessException(ErrorCode.AI_PRICE_CONFIG_INVALID, "只能删除手工价格");
    }
    entity.setEffectiveTo(Instant.now());
    priceRepository.save(entity);
    restoreAuto(entity.getProvider(), entity.getModel(), entity.getModality());
  }

  private void restoreAuto(String provider, String model, String modality) {
    Instant now = Instant.now();
    for (PriceSeed seed : source.load()) {
      if (seed.provider().equals(provider) && seed.model().equals(model)
          && seed.modality().equals(modality)) {
        priceRepository.save(AiModelPriceEntity.builder()
            .provider(provider)
            .model(model)
            .modality(modality)
            .inputPricePer1m(seed.inputPricePer1m())
            .outputPricePer1m(seed.outputPricePer1m())
            .cachedPricePer1m(seed.cachedPricePer1m())
            .unit("TOKEN")
            .currency("CNY")
            .source(PriceSource.AUTO)
            .effectiveFrom(now)
            .build());
        return;
      }
    }
  }

  private PriceItem toItem(AiModelPriceEntity entity) {
    return new PriceItem(
        entity.getId(),
        entity.getProvider(),
        entity.getModel(),
        entity.getModality(),
        entity.getInputPricePer1m(),
        entity.getOutputPricePer1m(),
        entity.getCachedPricePer1m(),
        entity.getSource().name(),
        entity.getEffectiveFrom(),
        entity.getEffectiveTo()
    );
  }
}
