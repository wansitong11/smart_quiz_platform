package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.resume.document.DocAsset;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.repository.PaperAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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

  public List<PaperAssetEntity> saveAll(Long paperId, int pageNo, List<DocAsset> assets,
                                        Integer anchorLine) {
    List<PaperAssetEntity> saved = new ArrayList<>();
    for (DocAsset asset : assets) {
      String storageKey = null;
      if (asset.bytes() != null && asset.bytes().length > 0) {
        storageKey = storageService.uploadBytes(asset.bytes(),
            paperId + "-" + asset.key() + ".jpg", "image/jpeg", "papers/" + paperId + "/assets");
      }
      PaperAssetEntity entity = new PaperAssetEntity();
      entity.setPaperId(paperId);
      entity.setPageNo(pageNo);
      entity.setAssetKey(asset.key());
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
      saved.add(assetRepository.save(entity));
    }
    return saved;
  }

  public PaperAssetEntity saveTile(Long paperId, int pageNo, String key, String parentKey,
                                   byte[] bytes, int width, int height) {
    String storageKey = storageService.uploadBytes(bytes, paperId + "-" + key + ".jpg",
        "image/jpeg", "papers/" + paperId + "/assets");
    PaperAssetEntity entity = new PaperAssetEntity();
    entity.setPaperId(paperId);
    entity.setPageNo(pageNo);
    entity.setAssetKey(key);
    entity.setParentKey(parentKey);
    entity.setKind("OPTION_ITEM");
    entity.setRole("OPTIONS");
    entity.setStorageKey(storageKey);
    entity.setWidth(width);
    entity.setHeight(height);
    return assetRepository.save(entity);
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
      }
    }
    assetRepository.deleteByPaperId(paperId);
  }
}
