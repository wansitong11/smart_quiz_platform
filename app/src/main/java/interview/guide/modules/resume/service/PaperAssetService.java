package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.resume.document.DocAsset;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.repository.PaperAssetRepository;
import interview.guide.modules.resume.log.PaperFlowLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 页面资源的存取：图片落 S3，元数据落 paper_assets。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperAssetService {

  private final PaperAssetRepository assetRepository;
  private final FileStorageService storageService;

  public PageAssetWriteResult replacePageGeneration(
      Long paperId,
      int pageNo,
      List<DocAsset> assets,
      Integer anchorLine,
      String sourceAttemptId
  ) {
    List<PaperAssetEntity> saved = new ArrayList<>();
    Map<String, String> keyMapping = new LinkedHashMap<>();
    String normalizedAttemptId = normalizeAttemptId(sourceAttemptId);
    for (DocAsset asset : assets) {
      PaperFlowLog.event("ASSET_SAVE_BEGIN paperId={}, pageNo={}, key={}, kind={}, role={}, "
              + "attempt={}, bytes={}, rect={}, width={}, height={}, layout={}, optionCount={}",
          paperId, pageNo, asset.key(), asset.kind(), asset.role(), normalizedAttemptId,
          asset.bytes() == null ? 0 : asset.bytes().length, asset.rect(), asset.width(),
          asset.height(), asset.layout(), asset.optionCount());
      String versionedKey = PaperAssetKey.versioned(asset.key(), normalizedAttemptId);
      String storageKey = null;
      if (asset.bytes() != null && asset.bytes().length > 0) {
        storageKey = storageService.uploadBytes(asset.bytes(),
            paperId + "-" + versionedKey + ".jpg", "image/jpeg",
            "papers/" + paperId + "/assets");
      }
      PaperAssetEntity entity = assetRepository.findByPaperIdAndAssetKey(paperId, versionedKey)
          .orElseGet(PaperAssetEntity::new);
      String previousStorageKey = entity.getStorageKey();
      entity.setPaperId(paperId);
      entity.setPageNo(pageNo);
      entity.setAssetKey(versionedKey);
      entity.setSourceAttemptId(normalizedAttemptId);
      entity.setParentKey(null);
      entity.setKind(asset.kind().name());
      entity.setRole(asset.role() == null ? null : asset.role().name());
      entity.setStorageKey(storageKey);
      entity.setAnchorLine(anchorLine);
      entity.setAnchorText(asset.anchorText());
      entity.setLayout(asset.layout());
      entity.setOptionCount(asset.optionCount());
      entity.setWidth(asset.width());
      entity.setHeight(asset.height());
      if (asset.rect() != null) {
        entity.setRectJson(String.format("[%s,%s,%s,%s]",
            asset.rect()[0], asset.rect()[1], asset.rect()[2], asset.rect()[3]));
      }
      PaperAssetEntity persisted = assetRepository.save(entity);
      saved.add(persisted);
      keyMapping.put(asset.key(), versionedKey);
      PaperFlowLog.event("ASSET_SAVE_END paperId={}, pageNo={}, key={}, storageKey={}, id={}",
          paperId, pageNo, versionedKey, storageKey, persisted.getId());
      if (previousStorageKey != null && storageKey != null
          && !previousStorageKey.equals(storageKey)) {
        try {
          storageService.deleteIfExists(previousStorageKey);
        } catch (Exception e) {
          log.warn("删除旧页面资源失败: key={}, error={}", previousStorageKey, e.getMessage());
          PaperFlowLog.error("ASSET_OLD_STORAGE_DELETE_FAILED key={}", e, previousStorageKey);
        }
      }
    }
    return new PageAssetWriteResult(Map.copyOf(keyMapping), List.copyOf(saved));
  }

  public PaperAssetEntity saveTile(Long paperId, int pageNo, String key, String parentKey,
                                   byte[] bytes, int width, int height,
                                   String sourceAttemptId) {
    String storageKey = storageService.uploadBytes(bytes, paperId + "-" + key + ".jpg",
        "image/jpeg", "papers/" + paperId + "/assets");
    PaperAssetEntity entity = assetRepository.findByPaperIdAndAssetKey(paperId, key)
        .orElseGet(PaperAssetEntity::new);
    String previousStorageKey = entity.getStorageKey();
    entity.setPaperId(paperId);
    entity.setPageNo(pageNo);
    entity.setAssetKey(key);
    entity.setSourceAttemptId(normalizeAttemptId(sourceAttemptId));
    entity.setParentKey(parentKey);
    entity.setKind("OPTION_ITEM");
    entity.setRole("OPTIONS");
    entity.setStorageKey(storageKey);
    entity.setWidth(width);
    entity.setHeight(height);
    PaperAssetEntity saved = assetRepository.save(entity);
    if (previousStorageKey != null && !previousStorageKey.equals(storageKey)) {
      try {
        storageService.deleteIfExists(previousStorageKey);
      } catch (Exception e) {
        log.warn("删除旧选项切片失败: key={}, error={}", previousStorageKey, e.getMessage());
        PaperFlowLog.error("ASSET_TILE_OLD_STORAGE_DELETE_FAILED key={}", e, previousStorageKey);
      }
    }
    return saved;
  }

  @Transactional
  public void deleteOptionTiles(Long paperId, String versionedParentKey) {
    List<PaperAssetEntity> tiles =
        assetRepository.findByPaperIdAndParentKeyAndKindOrderByIdAsc(
            paperId, versionedParentKey, "OPTION_ITEM");
    if (tiles.isEmpty()) {
      return;
    }
    List<String> storageKeys = tiles.stream()
        .map(PaperAssetEntity::getStorageKey)
        .filter(key -> key != null && !key.isBlank())
        .distinct()
        .toList();
    assetRepository.deleteAllInBatch(tiles);
    deleteStorageAfterCommit(storageKeys);
    PaperFlowLog.event("ASSET_OPTION_TILES_DELETE paperId={}, parentKey={}, count={}",
        paperId, versionedParentKey, tiles.size());
  }

  @Transactional
  public void deleteBySourceAttemptId(Long paperId, String sourceAttemptId) {
    List<PaperAssetEntity> assets =
        assetRepository.findByPaperIdAndSourceAttemptIdOrderByIdAsc(paperId, sourceAttemptId);
    if (assets.isEmpty()) {
      return;
    }
    List<String> storageKeys = assets.stream()
        .map(PaperAssetEntity::getStorageKey)
        .filter(key -> key != null && !key.isBlank())
        .distinct()
        .toList();
    assetRepository.deleteAllInBatch(assets);
    deleteStorageAfterCommit(storageKeys);
    PaperFlowLog.warn("ASSET_ATTEMPT_DELETE paperId={}, attempt={}, count={}",
        paperId, sourceAttemptId, assets.size());
  }

  public void updateCutOffsets(PaperAssetEntity asset, String cutOffsets) {
    asset.setCutOffsets(cutOffsets);
    assetRepository.save(asset);
  }

  public List<PaperAssetEntity> list(Long paperId) {
    return assetRepository.findByPaperIdOrderByIdAsc(paperId);
  }

  public Optional<PaperAssetEntity> find(Long paperId, String assetKey) {
    return assetRepository.findByPaperIdAndAssetKey(paperId, assetKey);
  }

  public byte[] download(PaperAssetEntity asset) {
    if (asset.getStorageKey() == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "该资源没有图片");
    }
    return storageService.downloadFile(asset.getStorageKey());
  }

  public void deleteByPaperId(Long paperId) {
    for (PaperAssetEntity asset : list(paperId)) {
      try {
        storageService.deleteIfExists(asset.getStorageKey());
      } catch (Exception e) {
        log.warn("删除资源失败: key={}, error={}", asset.getStorageKey(), e.getMessage());
        PaperFlowLog.error("ASSET_DELETE_FAILED key={}", e, asset.getStorageKey());
      }
    }
    assetRepository.deleteByPaperId(paperId);
  }

  private String normalizeAttemptId(String sourceAttemptId) {
    return sourceAttemptId == null || sourceAttemptId.isBlank()
        ? PaperAssetKey.INITIAL_ATTEMPT
        : sourceAttemptId;
  }

  private void deleteStorageAfterCommit(List<String> storageKeys) {
    if (storageKeys.isEmpty()) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      deleteStorageQuietly(storageKeys);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            deleteStorageQuietly(storageKeys);
          }
        }
    );
  }

  private void deleteStorageQuietly(List<String> storageKeys) {
    for (String storageKey : storageKeys) {
      try {
        storageService.deleteIfExists(storageKey);
      } catch (Exception e) {
        log.warn("删除资源失败（数据库已完成变更）: key={}", storageKey, e);
        PaperFlowLog.error("ASSET_DELETE_STORAGE_FAILED key={}", e, storageKey);
      }
    }
  }

  public record PageAssetWriteResult(
      Map<String, String> originalKeyToVersionedKey,
      List<PaperAssetEntity> entities
  ) {
  }
}
