package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.PaperAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PaperAssetRepository extends JpaRepository<PaperAssetEntity, Long> {

  List<PaperAssetEntity> findByPaperIdOrderByIdAsc(Long paperId);

  Optional<PaperAssetEntity> findByPaperIdAndAssetKey(Long paperId, String assetKey);

  List<PaperAssetEntity> findByPaperIdAndParentKeyAndKindOrderByIdAsc(
      Long paperId, String parentKey, String kind);

  List<PaperAssetEntity> findByPaperIdAndSourceAttemptIdOrderByIdAsc(
      Long paperId, String sourceAttemptId);

  @Transactional
  @Modifying
  void deleteByPaperId(Long paperId);
}
