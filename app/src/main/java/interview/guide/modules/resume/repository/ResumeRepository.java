package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.PracticePaperEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;

/**
 * 简历Repository
 */
@Repository
public interface ResumeRepository extends JpaRepository<PracticePaperEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PracticePaperEntity r WHERE r.id = :id")
    Optional<PracticePaperEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * 只取归属用户，避免异步任务补上下文时把试卷正文和结构快照整行加载出来。
     */
    @Query("SELECT r.ownerId FROM PracticePaperEntity r WHERE r.id = :id")
    Optional<Long> findOwnerIdById(@Param("id") Long id);
    
    /**
     * 根据文件哈希查找简历（用于去重）
     */
    Optional<PracticePaperEntity> findByFileHash(String fileHash);

    // ========== 归属隔离查询（ownerId 为 null 表示管理员查看全部，见 OwnerScope） ==========

    @Query("SELECT r FROM PracticePaperEntity r WHERE r.id = :id AND (:ownerId IS NULL OR r.ownerId = :ownerId)")
    Optional<PracticePaperEntity> findByIdScoped(@Param("id") Long id, @Param("ownerId") Long ownerId);

    @Query("SELECT r FROM PracticePaperEntity r WHERE (:ownerId IS NULL OR r.ownerId = :ownerId) ORDER BY r.uploadedAt DESC")
    List<PracticePaperEntity> findAllScoped(@Param("ownerId") Long ownerId);

    @Query("SELECT r FROM PracticePaperEntity r WHERE r.fileHash = :fileHash AND (:ownerId IS NULL OR r.ownerId = :ownerId)")
    Optional<PracticePaperEntity> findByFileHashScoped(@Param("fileHash") String fileHash,
                                                       @Param("ownerId") Long ownerId);
    
    /**
     * 检查文件哈希是否存在
     */
    boolean existsByFileHash(String fileHash);

    // ========== P1-07 条件状态更新（终态不被覆盖，多实例安全） ==========

    /** PENDING → PROCESSING 条件领取，返回是否领取成功 */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.analysisStatus = 'PROCESSING', "
        + "r.analysisAttemptId = :attemptId, r.analysisUpdatedAt = :now "
        + "WHERE r.id = :id AND r.analysisStatus = 'PENDING'")
    int tryMarkAnalyzeProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                 @Param("now") LocalDateTime now);

    /** 心跳：仍为 PROCESSING 才推进进展时间 */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.analysisUpdatedAt = :now "
        + "WHERE r.id = :id AND r.analysisStatus = 'PROCESSING' "
        + "AND r.analysisAttemptId = :attemptId")
    int heartbeatAnalyzeProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                   @Param("now") LocalDateTime now);

    /** PROCESSING → COMPLETED（非 PROCESSING 时 no-op） */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.analysisStatus = 'COMPLETED', r.analysisError = null, "
        + "r.analysisAttemptId = null, r.analysisUpdatedAt = :now "
        + "WHERE r.id = :id AND r.analysisStatus = 'PROCESSING' "
        + "AND r.analysisAttemptId = :attemptId")
    int completeAnalyzeIfProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                    @Param("now") LocalDateTime now);

    /** 当前执行代次 PROCESSING → FAILED；已确认的识图卷同步恢复到 READY。 */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.analysisStatus = 'FAILED', r.analysisError = :error, "
        + "r.analysisAttemptId = null, r.analysisUpdatedAt = :now, "
        + "r.recognitionStatus = CASE WHEN r.recognitionStatus = 'EXTRACTING' "
        + "THEN 'READY' ELSE r.recognitionStatus END "
        + "WHERE r.id = :id AND r.analysisStatus = 'PROCESSING' "
        + "AND r.analysisAttemptId = :attemptId")
    int failAnalyzeIfProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                @Param("error") String error, @Param("now") LocalDateTime now);

    /** 尚未领取的 PENDING → FAILED；已确认的识图卷同步恢复到 READY。 */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.analysisStatus = 'FAILED', r.analysisError = :error, "
        + "r.analysisAttemptId = null, r.analysisUpdatedAt = :now, "
        + "r.recognitionStatus = CASE WHEN r.recognitionStatus = 'EXTRACTING' "
        + "THEN 'READY' ELSE r.recognitionStatus END "
        + "WHERE r.id = :id AND r.analysisStatus = 'PENDING'")
    int failAnalyzeIfPending(@Param("id") Long id, @Param("error") String error,
                             @Param("now") LocalDateTime now);

    /** PROCESSING → PENDING（重试重置） */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.analysisStatus = 'PENDING', r.analysisAttemptId = null, "
        + "r.analysisUpdatedAt = :now WHERE r.id = :id AND r.analysisStatus = 'PROCESSING' "
        + "AND r.analysisAttemptId = :attemptId")
    int resetAnalyzeToPending(@Param("id") Long id, @Param("attemptId") String attemptId,
                              @Param("now") LocalDateTime now);

    /** 恢复补投前原子推进时间（PENDING 且早于阈值） */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.analysisUpdatedAt = :now WHERE r.id = :id AND r.analysisStatus = 'PENDING' AND r.analysisUpdatedAt < :threshold "
        + "AND r.resumeText IS NOT NULL AND TRIM(r.resumeText) <> '' "
        + "AND r.recognitionStatus IN ('NONE','EXTRACTING')")
    int touchQueuedAnalyzeForRecovery(@Param("id") Long id, @Param("threshold") LocalDateTime threshold, @Param("now") LocalDateTime now);

    /** PROCESSING 无进展超阈值 → PENDING */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.analysisStatus = 'PENDING', r.analysisAttemptId = null, "
        + "r.analysisUpdatedAt = :now WHERE r.id = :id AND r.analysisStatus = 'PROCESSING' "
        + "AND r.analysisUpdatedAt < :threshold "
        + "AND r.resumeText IS NOT NULL AND TRIM(r.resumeText) <> '' "
        + "AND r.recognitionStatus IN ('NONE','EXTRACTING')")
    int resetStaleAnalyzeProcessing(@Param("id") Long id, @Param("threshold") LocalDateTime threshold, @Param("now") LocalDateTime now);

    /** 恢复计数 +1 */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.analysisRecoveryCount = r.analysisRecoveryCount + 1 WHERE r.id = :id")
    int incrementAnalysisRecoveryCount(@Param("id") Long id);

    /** 手动重试清零恢复计数 */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.analysisRecoveryCount = 0 WHERE r.id = :id")
    int resetAnalysisRecoveryCount(@Param("id") Long id);

    /** 原子推进试卷内容代次；多个页面并行完成时不能使用 Java 读后写。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PracticePaperEntity r SET r.contentRevision = r.contentRevision + 1, "
        + "r.analysisUpdatedAt = :now WHERE r.id = :paperId")
    int incrementContentRevision(@Param("paperId") Long paperId,
                                 @Param("now") LocalDateTime now);

    /** 卡住任务扫描（带每轮上限） */
    @Query("SELECT r FROM PracticePaperEntity r WHERE r.analysisStatus = :status AND r.analysisUpdatedAt < :threshold ORDER BY r.analysisUpdatedAt ASC")
    List<PracticePaperEntity> findStaleByAnalysisStatus(@Param("status") interview.guide.common.model.AsyncTaskStatus status,
                                                @Param("threshold") LocalDateTime threshold,
                                                Pageable pageable);

    /**
     * 切题卡住任务扫描：只返回「正文就绪」且识别状态为纯文字（NONE）或已确认切题（EXTRACTING）的记录。
     * 未确认识别卷的 analysisStatus 也可能是 PENDING，必须在这里排除。
     */
    @Query("SELECT r FROM PracticePaperEntity r WHERE r.analysisStatus = :status "
        + "AND r.resumeText IS NOT NULL AND TRIM(r.resumeText) <> '' "
        + "AND r.recognitionStatus IN ('NONE','EXTRACTING') "
        + "AND r.analysisUpdatedAt < :threshold ORDER BY r.analysisUpdatedAt ASC")
    List<PracticePaperEntity> findStaleExtractableByAnalysisStatus(
                                                @Param("status") interview.guide.common.model.AsyncTaskStatus status,
                                                @Param("threshold") LocalDateTime threshold,
                                                Pageable pageable);

    // ========== 图片 / 扫描件识别：与切题同构的条件状态更新 ==========

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.recognitionStatus = 'PROCESSING', "
        + "r.recognitionAttemptId = :attemptId, r.recognitionUpdatedAt = :now "
        + "WHERE r.id = :id AND r.recognitionStatus = 'PENDING'")
    int tryMarkRecognitionProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                     @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.recognitionUpdatedAt = :now "
        + "WHERE r.id = :id AND r.recognitionStatus = 'PROCESSING' "
        + "AND r.recognitionAttemptId = :attemptId")
    int heartbeatRecognitionProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                       @Param("now") LocalDateTime now);

    /** PROCESSING → NEEDS_REVIEW（识别结束，等待预览校对）。 */
    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.recognitionStatus = 'NEEDS_REVIEW', r.recognitionError = null, "
        + "r.recognitionAttemptId = null, r.recognitionUpdatedAt = :now "
        + "WHERE r.id = :id AND r.recognitionStatus = 'PROCESSING' "
        + "AND r.recognitionAttemptId = :attemptId")
    int completeRecognitionIfProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                        @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.recognitionStatus = 'FAILED', r.recognitionError = :error, "
        + "r.recognitionAttemptId = null, r.recognitionUpdatedAt = :now "
        + "WHERE r.id = :id AND r.recognitionStatus = 'PROCESSING' "
        + "AND r.recognitionAttemptId = :attemptId")
    int failRecognitionIfProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                    @Param("error") String error, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.recognitionStatus = 'FAILED', r.recognitionError = :error, "
        + "r.recognitionAttemptId = null, r.recognitionUpdatedAt = :now "
        + "WHERE r.id = :id AND r.recognitionStatus = 'PENDING'")
    int failRecognitionIfPending(@Param("id") Long id, @Param("error") String error,
                                 @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.recognitionStatus = 'PENDING', r.recognitionAttemptId = null, "
        + "r.recognitionUpdatedAt = :now WHERE r.id = :id AND r.recognitionStatus = 'PROCESSING' "
        + "AND r.recognitionAttemptId = :attemptId")
    int resetRecognitionToPending(@Param("id") Long id, @Param("attemptId") String attemptId,
                                  @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.recognitionUpdatedAt = :now WHERE r.id = :id "
        + "AND r.recognitionStatus = 'PENDING' AND r.recognitionUpdatedAt < :threshold")
    int touchQueuedRecognitionForRecovery(@Param("id") Long id,
                                          @Param("threshold") LocalDateTime threshold,
                                          @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.recognitionStatus = 'PENDING', r.recognitionAttemptId = null, "
        + "r.recognitionUpdatedAt = :now WHERE r.id = :id AND r.recognitionStatus = 'PROCESSING' "
        + "AND r.recognitionUpdatedAt < :threshold")
    int resetStaleRecognitionProcessing(@Param("id") Long id,
                                        @Param("threshold") LocalDateTime threshold,
                                        @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.recognitionRecoveryCount = r.recognitionRecoveryCount + 1 WHERE r.id = :id")
    int incrementRecognitionRecoveryCount(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.recognitionRecoveryCount = 0 WHERE r.id = :id")
    int resetRecognitionRecoveryCount(@Param("id") Long id);

    @Query("SELECT r FROM PracticePaperEntity r WHERE r.recognitionStatus = :status "
        + "AND r.recognitionUpdatedAt < :threshold ORDER BY r.recognitionUpdatedAt ASC")
    List<PracticePaperEntity> findStaleByRecognitionStatus(
        @Param("status") interview.guide.modules.resume.model.RecognitionStatus status,
        @Param("threshold") LocalDateTime threshold,
        Pageable pageable);

    // ========== 输入处理（E 阶段）：与切题 / 识别同构的条件状态更新 ==========

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.inputStatus = 'PROCESSING', "
        + "r.inputAttemptId = :attemptId, r.inputUpdatedAt = :now "
        + "WHERE r.id = :id AND r.inputStatus = 'PENDING'")
    int tryMarkInputProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                               @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.inputUpdatedAt = :now "
        + "WHERE r.id = :id AND r.inputStatus = 'PROCESSING' "
        + "AND r.inputAttemptId = :attemptId")
    int heartbeatInputProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                 @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.inputStatus = 'COMPLETED', r.inputError = null, "
        + "r.inputAttemptId = null, r.inputUpdatedAt = :now "
        + "WHERE r.id = :id AND r.inputStatus = 'PROCESSING' "
        + "AND r.inputAttemptId = :attemptId")
    int completeInputIfProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                  @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.inputStatus = 'FAILED', r.inputError = :error, "
        + "r.inputAttemptId = null, r.inputUpdatedAt = :now "
        + "WHERE r.id = :id AND r.inputStatus = 'PROCESSING' "
        + "AND r.inputAttemptId = :attemptId")
    int failInputIfProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                              @Param("error") String error, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.inputStatus = 'FAILED', r.inputError = :error, "
        + "r.inputAttemptId = null, r.inputUpdatedAt = :now "
        + "WHERE r.id = :id AND r.inputStatus = 'PENDING'")
    int failInputIfPending(@Param("id") Long id, @Param("error") String error,
                           @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.inputStatus = 'PENDING', r.inputAttemptId = null, "
        + "r.inputUpdatedAt = :now WHERE r.id = :id AND r.inputStatus = 'PROCESSING' "
        + "AND r.inputAttemptId = :attemptId")
    int resetInputToPending(@Param("id") Long id, @Param("attemptId") String attemptId,
                            @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.inputStatus = 'PENDING', r.inputError = null, "
        + "r.inputAttemptId = null, r.inputUpdatedAt = :now "
        + "WHERE r.id = :id AND r.inputStatus = 'FAILED'")
    int retryInputFromFailed(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.inputUpdatedAt = :now WHERE r.id = :id "
        + "AND r.inputStatus = 'PENDING' AND r.inputUpdatedAt < :threshold")
    int touchQueuedInputForRecovery(@Param("id") Long id,
                                    @Param("threshold") LocalDateTime threshold,
                                    @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.inputRecoveryCount = r.inputRecoveryCount + 1 WHERE r.id = :id")
    int incrementInputRecoveryCount(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE PracticePaperEntity r SET r.inputRecoveryCount = 0 WHERE r.id = :id")
    int resetInputRecoveryCount(@Param("id") Long id);

    @Query("SELECT r FROM PracticePaperEntity r WHERE r.inputStatus = :status "
        + "AND r.inputUpdatedAt < :threshold ORDER BY r.inputUpdatedAt ASC")
    List<PracticePaperEntity> findStaleByInputStatus(
        @Param("status") interview.guide.common.model.AsyncTaskStatus status,
        @Param("threshold") LocalDateTime threshold,
        Pageable pageable);
}
