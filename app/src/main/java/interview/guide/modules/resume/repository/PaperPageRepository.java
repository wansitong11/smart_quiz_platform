package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperPageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaperPageRepository extends JpaRepository<PaperPageEntity, Long> {

  List<PaperPageEntity> findByPaperIdOrderByPageNoAsc(Long paperId);

  Optional<PaperPageEntity> findByPaperIdAndPageNo(Long paperId, int pageNo);

  boolean existsByPaperIdAndPageNoAndStatusAndRecognitionAttemptId(
      Long paperId,
      int pageNo,
      PageStatus status,
      String recognitionAttemptId
  );

  long countByPaperId(Long paperId);

  @Transactional
  @Modifying
  @Query("UPDATE PaperPageEntity p SET p.status = 'PROCESSING', "
      + "p.recognitionAttemptId = :attemptId, p.updatedAt = :now "
      + "WHERE p.paperId = :paperId AND p.pageNo = :pageNo AND p.status = 'PENDING'")
  int claimPage(
      @Param("paperId") Long paperId,
      @Param("pageNo") int pageNo,
      @Param("attemptId") String attemptId,
      @Param("now") LocalDateTime now
  );

  @Transactional
  @Modifying
  @Query("UPDATE PaperPageEntity p SET p.status = 'READY', p.textContent = :text, "
      + "p.hasFigure = :hasFigure, p.notes = :notes, p.model = :model, "
      + "p.assetsJson = :assetsJson, p.error = null, p.diagnosticJson = null, "
      + "p.attempt = p.attempt + 1, p.recognitionAttemptId = null, p.updatedAt = :now "
      + "WHERE p.paperId = :paperId AND p.pageNo = :pageNo "
      + "AND p.status = 'PROCESSING' AND p.recognitionAttemptId = :attemptId")
  int markPageReadyIfClaimed(
      @Param("paperId") Long paperId,
      @Param("pageNo") int pageNo,
      @Param("attemptId") String attemptId,
      @Param("text") String text,
      @Param("hasFigure") boolean hasFigure,
      @Param("notes") String notes,
      @Param("model") String model,
      @Param("assetsJson") String assetsJson,
      @Param("now") LocalDateTime now
  );

  @Transactional
  @Modifying
  @Query("UPDATE PaperPageEntity p SET p.status = 'FAILED', p.error = :error, "
      + "p.attempt = p.attempt + 1, p.recognitionAttemptId = null, p.updatedAt = :now "
      + "WHERE p.paperId = :paperId AND p.pageNo = :pageNo "
      + "AND p.status = 'PROCESSING' AND p.recognitionAttemptId = :attemptId")
  int markPageFailedIfClaimed(
      @Param("paperId") Long paperId,
      @Param("pageNo") int pageNo,
      @Param("attemptId") String attemptId,
      @Param("error") String error,
      @Param("now") LocalDateTime now
  );

  /**
   * 失败路径在抛异常前写入结构化诊断；随后 markPageFailedIfClaimed 再写状态与文案，
   * 两者都以当前页代次为准，避免旧代次污染新结果。
   */
  @Transactional
  @Modifying
  @Query("UPDATE PaperPageEntity p SET p.diagnosticJson = :diagnosticJson, "
      + "p.updatedAt = :now "
      + "WHERE p.paperId = :paperId AND p.pageNo = :pageNo "
      + "AND p.status = 'PROCESSING' AND p.recognitionAttemptId = :attemptId")
  int writePageDiagnostic(
      @Param("paperId") Long paperId,
      @Param("pageNo") int pageNo,
      @Param("attemptId") String attemptId,
      @Param("diagnosticJson") String diagnosticJson,
      @Param("now") LocalDateTime now
  );

  @Transactional
  @Modifying
  @Query("UPDATE PaperPageEntity p SET p.status = 'PENDING', "
      + "p.recognitionAttemptId = null, p.updatedAt = :now "
      + "WHERE p.paperId = :paperId AND p.recognitionAttemptId IN :attemptIds "
      + "AND p.status = 'PROCESSING'")
  int resetClaimedPagesToPending(
      @Param("paperId") Long paperId,
      @Param("attemptIds") List<String> attemptIds,
      @Param("now") LocalDateTime now
  );

  @Transactional
  @Modifying
  @Query("UPDATE PaperPageEntity p SET p.status = 'PENDING', "
      + "p.recognitionAttemptId = null, p.updatedAt = :now "
      + "WHERE p.paperId = :paperId AND p.status = 'PROCESSING' "
      + "AND p.updatedAt < :threshold")
  int resetStaleProcessingPages(
      @Param("paperId") Long paperId,
      @Param("threshold") LocalDateTime threshold,
      @Param("now") LocalDateTime now
  );

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
