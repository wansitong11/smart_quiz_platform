package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUserHolder;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.ProviderOption;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelAssignmentItem;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelAssignmentRequest;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelBatchAssignRequest;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelOptions;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelPageResult;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.model.UserModelOverrideEntity;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.repository.UserModelOverrideRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员按用户分配文字/识图模型。
 */
@Service
@RequiredArgsConstructor
public class UserModelAssignmentService {

  private final UserModelOverrideRepository overrideRepository;
  private final LlmProviderRepository providerRepository;
  private final UserRepository userRepository;
  private final LlmProviderRegistry llmProviderRegistry;

  @Transactional(readOnly = true)
  public UserModelPageResult listUsers(String keyword, int page, int size) {
    int safePage = Math.max(0, page);
    int safeSize = Math.min(Math.max(1, size), 100);
    Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by("id"));
    String normalized = keyword == null ? "" : keyword.trim();
    Page<UserEntity> users = normalized.isEmpty()
        ? userRepository.findAll(pageable)
        : userRepository.findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
            normalized, normalized, pageable);

    List<Long> userIds = users.getContent().stream().map(UserEntity::getId).toList();
    Map<Long, Map<String, UserModelOverrideEntity>> overrides = loadOverrides(userIds);
    Map<String, String> providerModels = new HashMap<>();
    List<UserModelAssignmentItem> items = users.getContent().stream()
        .map(user -> toItem(user, overrides.get(user.getId()), providerModels))
        .toList();
    return new UserModelPageResult(items, users.getTotalElements(), safePage, safeSize);
  }

  @Transactional(readOnly = true)
  public UserModelOptions options() {
    List<LlmProviderEntity> enabled = providerRepository.findByEnabledTrueOrderByIdAsc();
    List<ProviderOption> textProviders = enabled.stream()
        .map(provider -> new ProviderOption(provider.getId(), provider.getModel()))
        .toList();
    List<ProviderOption> visionProviders = enabled.stream()
        .filter(LlmProviderEntity::isSupportsVision)
        .map(provider -> new ProviderOption(provider.getId(), provider.getModel()))
        .toList();
    return new UserModelOptions(textProviders, visionProviders);
  }

  @Transactional
  public void assign(Long userId, UserModelAssignmentRequest request) {
    requireUser(userId);
    Long adminId = CurrentUserHolder.currentUserId();
    applyDimension(userId, UserModelOverrideEntity.TEXT, request.textProviderId(), adminId);
    applyDimension(userId, UserModelOverrideEntity.VISION, request.visionProviderId(), adminId);
  }

  @Transactional
  public void assignBatch(UserModelBatchAssignRequest request) {
    List<Long> userIds = request.userIds().stream().filter(Objects::nonNull).distinct().toList();
    if (userIds.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择至少一个用户");
    }
    userIds.forEach(this::requireUser);
    validateProvider(request.textProviderId(), UserModelOverrideEntity.TEXT);
    validateProvider(request.visionProviderId(), UserModelOverrideEntity.VISION);

    Long adminId = CurrentUserHolder.currentUserId();
    for (Long userId : userIds) {
      applyDimension(userId, UserModelOverrideEntity.TEXT, request.textProviderId(), adminId);
      applyDimension(userId, UserModelOverrideEntity.VISION, request.visionProviderId(), adminId);
    }
  }

  private Map<Long, Map<String, UserModelOverrideEntity>> loadOverrides(List<Long> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, Map<String, UserModelOverrideEntity>> result = new HashMap<>();
    for (UserModelOverrideEntity override : overrideRepository.findAllByUserIdIn(userIds)) {
      result.computeIfAbsent(override.getUserId(), key -> new HashMap<>())
          .put(override.getModality(), override);
    }
    return result;
  }

  private UserModelAssignmentItem toItem(
      UserEntity user,
      Map<String, UserModelOverrideEntity> byModality,
      Map<String, String> providerModels) {
    Map<String, UserModelOverrideEntity> overrides = byModality == null ? Map.of() : byModality;
    UserModelOverrideEntity text = overrides.get(UserModelOverrideEntity.TEXT);
    UserModelOverrideEntity vision = overrides.get(UserModelOverrideEntity.VISION);
    String textEffectiveProviderId = llmProviderRegistry.resolveChatProviderIdForUser(user.getId());
    String visionEffectiveProviderId =
        llmProviderRegistry.resolveVisionProviderIdForUser(user.getId());
    String textEffectiveModel = providerModel(textEffectiveProviderId, providerModels);
    String visionEffectiveModel = providerModel(visionEffectiveProviderId, providerModels);
    LocalDateTime updatedAt = Stream.of(text, vision)
        .filter(Objects::nonNull)
        .map(UserModelOverrideEntity::getUpdatedAt)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(null);
    return new UserModelAssignmentItem(
        user.getId(),
        user.getUsername(),
        user.getDisplayName(),
        text == null ? null : text.getProviderId(),
        textEffectiveProviderId,
        textEffectiveModel,
        text == null || !Objects.equals(text.getProviderId(), textEffectiveProviderId),
        vision == null ? null : vision.getProviderId(),
        visionEffectiveProviderId,
        visionEffectiveModel,
        vision == null || !Objects.equals(vision.getProviderId(), visionEffectiveProviderId),
        updatedAt);
  }

  private String providerModel(String providerId, Map<String, String> cache) {
    if (isBlank(providerId)) {
      return null;
    }
    if (cache.containsKey(providerId)) {
      return cache.get(providerId);
    }
    String model = providerRepository.findById(providerId)
        .map(LlmProviderEntity::getModel)
        .orElse(null);
    cache.put(providerId, model);
    return model;
  }

  private void applyDimension(Long userId, String modality, String providerId, Long adminId) {
    if (isBlank(providerId)) {
      overrideRepository.deleteByUserIdAndModality(userId, modality);
      return;
    }
    validateProvider(providerId, modality);
    UserModelOverrideEntity entity = overrideRepository
        .findByUserIdAndModality(userId, modality)
        .orElseGet(() -> UserModelOverrideEntity.builder()
            .userId(userId)
            .modality(modality)
            .build());
    entity.setProviderId(providerId.trim());
    entity.setUpdatedBy(adminId);
    overrideRepository.save(entity);
  }

  private void validateProvider(String providerId, String modality) {
    if (isBlank(providerId)) {
      return;
    }
    LlmProviderEntity provider = providerRepository.findById(providerId.trim())
        .filter(LlmProviderEntity::isEnabled)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
            "Provider '" + providerId + "' 不存在或已禁用"));
    if (UserModelOverrideEntity.VISION.equals(modality) && !provider.isSupportsVision()) {
      throw new BusinessException(ErrorCode.VISION_PROVIDER_NOT_CONFIGURED,
          "Provider '" + providerId + "' 未标记为支持图片输入");
    }
  }

  private void requireUser(Long userId) {
    if (!userRepository.existsById(userId)) {
      throw new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户 " + userId + " 不存在");
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
