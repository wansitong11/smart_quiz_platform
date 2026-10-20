package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperPageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PaperPageRepository extends JpaRepository<PaperPageEntity, Long> {

  List<PaperPageEntity> findByPaperIdOrderByPageNoAsc(Long paperId);

  Optional<PaperPageEntity> findByPaperIdAndPageNo(Long paperId, int pageNo);

  long countByPaperId(Long paperId);

  @Transactional
  @Modifying
  void deleteByPaperId(Long paperId);

  @Transactional
  @Modifying
  @Query("UPDATE PaperPageEntity p SET p.pageNo = p.pageNo + 1000 WHERE p.paperId = :paperId AND p.pageNo > :pageNo")
  int shiftPageNumbersUp(@Param("paperId") Long paperId, @Param("pageNo") int pageNo);

  /** 两段式重排，避免单条 UPDATE 期间撞上 (paper_id, page_no) 唯一约束。 */
  @Transactional
  @Modifying
  @Query("UPDATE PaperPageEntity p SET p.pageNo = p.pageNo - 1001 WHERE p.paperId = :paperId AND p.pageNo > :pageNo + 1000")
  int shiftPageNumbersDown(@Param("paperId") Long paperId, @Param("pageNo") int pageNo);
}
