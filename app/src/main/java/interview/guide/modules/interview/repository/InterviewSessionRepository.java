package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity.SessionStatus;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.PracticePaperEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 面试会话Repository
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<PracticeSessionEntity, Long> {

    /**
     * 根据会话ID查找
     */
    Optional<PracticeSessionEntity> findBySessionId(String sessionId);

    // ========== 归属隔离查询（ownerId 为 null 表示管理员查看全部） ==========

    @Query("SELECT s FROM PracticeSessionEntity s WHERE s.sessionId = :sessionId AND (:ownerId IS NULL OR s.ownerId = :ownerId)")
    Optional<PracticeSessionEntity> findBySessionIdScoped(@Param("sessionId") String sessionId,
                                                          @Param("ownerId") Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PracticeSessionEntity s WHERE s.sessionId = :sessionId")
    Optional<PracticeSessionEntity> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

    @Query("SELECT s FROM PracticeSessionEntity s WHERE (:ownerId IS NULL OR s.ownerId = :ownerId) ORDER BY s.createdAt DESC")
    List<PracticeSessionEntity> findAllScoped(@Param("ownerId") Long ownerId);

    @Query("SELECT s FROM PracticeSessionEntity s WHERE s.resumeId = :resumeId AND (:ownerId IS NULL OR s.ownerId = :ownerId) ORDER BY s.createdAt DESC")
    List<PracticeSessionEntity> findByResumeIdScoped(@Param("resumeId") Long resumeId,
                                                     @Param("ownerId") Long ownerId);

    Optional<PracticeSessionEntity> findByRequestId(String requestId);

    @Query("""
        SELECT COUNT(s) > 0
          FROM PracticeSessionEntity s
         WHERE s.resumeId = :paperId
           AND s.sourceType = :sourceType
           AND (
                s.status = :inProgress
                OR (
                  s.status = :completed
                  AND (s.evaluateStatus IS NULL OR s.evaluateStatus <> :completedEvaluateStatus)
                )
           )
        """)
    boolean existsActivePaperAttempt(
        @Param("paperId") Long paperId,
        @Param("sourceType") String sourceType,
        @Param("inProgress") SessionStatus inProgress,
        @Param("completed") SessionStatus completed,
        @Param("completedEvaluateStatus") AsyncTaskStatus completedEvaluateStatus
    );

    @Query("""
        SELECT s
          FROM PracticeSessionEntity s
         WHERE s.resumeId = :paperId
           AND s.sourceType = :sourceType
           AND s.status = :inProgress
           AND COALESCE(s.currentQuestionIndex, 0) = 0
           AND NOT EXISTS (
             SELECT a.id
               FROM PracticeAnswerEntity a
              WHERE a.session = s
           )
         ORDER BY s.createdAt DESC
        """)
    List<PracticeSessionEntity> findUnstartedInProgressPaperAttempts(
        @Param("paperId") Long paperId,
        @Param("sourceType") String sourceType,
        @Param("inProgress") SessionStatus inProgress
    );

    /**
     * 根据会话ID查找（同时加载关联的简历）
     */
    @Query("SELECT s FROM PracticeSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<PracticeSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);
    
    /**
     * 根据简历查找所有面试记录
     */
    List<PracticeSessionEntity> findByResumeOrderByCreatedAtDesc(PracticePaperEntity resume);
    
    /**
     * 根据简历ID查找所有面试记录
     */
    List<PracticeSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

    /**
     * 根据简历ID查找最近的面试记录（用于历史题去重）
     */
    List<PracticeSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);
    
    /**
     * 查找简历的未完成面试（CREATED或IN_PROGRESS状态）
     */
    Optional<PracticeSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, 
        List<SessionStatus> statuses
    );
    
    /**
     * 根据简历ID和状态查找会话
     */
    Optional<PracticeSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 查找所有面试会话（按创建时间倒序）
     */
    List<PracticeSessionEntity> findAllByOrderByCreatedAtDesc();

    /**
     * 根据 skillId 查找最近的面试记录（用于通用模式历史题去重）
     */
    List<PracticeSessionEntity> findTop10BySkillIdOrderByCreatedAtDesc(String skillId);

    /**
     * 根据 resumeId + skillId 查找最近的面试记录（精确匹配）
     */
    List<PracticeSessionEntity> findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long resumeId, String skillId);

    // ========== 异步出题（D 阶段）：与切题 / 识别同构的条件状态更新 ==========

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.questionStatus = 'PROCESSING', "
        + "s.questionAttemptId = :attemptId, s.questionUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PENDING'")
    int tryMarkQuestionGenProcessing(@Param("sessionId") String sessionId,
                                     @Param("attemptId") String attemptId,
                                     @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.questionUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PROCESSING' "
        + "AND s.questionAttemptId = :attemptId")
    int heartbeatQuestionGenProcessing(@Param("sessionId") String sessionId,
                                       @Param("attemptId") String attemptId,
                                       @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.paperJson = :paperJson, "
        + "s.totalQuestions = :totalQuestions, s.questionStatus = 'COMPLETED', "
        + "s.questionError = null, s.questionAttemptId = null, s.questionUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PROCESSING' "
        + "AND s.questionAttemptId = :attemptId")
    int completeQuestionGenIfProcessing(@Param("sessionId") String sessionId,
                                        @Param("attemptId") String attemptId,
                                        @Param("paperJson") String paperJson,
                                        @Param("totalQuestions") int totalQuestions,
                                        @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.questionStatus = 'FAILED', "
        + "s.questionError = :error, s.questionAttemptId = null, s.questionUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PROCESSING' "
        + "AND s.questionAttemptId = :attemptId")
    int failQuestionGenIfProcessing(@Param("sessionId") String sessionId,
                                    @Param("attemptId") String attemptId,
                                    @Param("error") String error,
                                    @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.questionStatus = 'FAILED', "
        + "s.questionError = :error, s.questionAttemptId = null, s.questionUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PENDING'")
    int failQuestionGenIfPending(@Param("sessionId") String sessionId,
                                 @Param("error") String error,
                                 @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.questionStatus = 'PENDING', "
        + "s.questionAttemptId = null, s.questionUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PROCESSING' "
        + "AND s.questionAttemptId = :attemptId")
    int resetQuestionGenToPending(@Param("sessionId") String sessionId,
                                  @Param("attemptId") String attemptId,
                                  @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.questionStatus = 'PENDING', "
        + "s.questionError = null, s.questionUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'FAILED'")
    int retryQuestionGenFromFailed(@Param("sessionId") String sessionId,
                                   @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.questionUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.questionStatus = 'PENDING' "
        + "AND s.questionUpdatedAt < :threshold")
    int touchQueuedQuestionGenForRecovery(@Param("sessionId") String sessionId,
                                          @Param("threshold") LocalDateTime threshold,
                                          @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.questionRecoveryCount = "
        + "s.questionRecoveryCount + 1 WHERE s.sessionId = :sessionId")
    int incrementQuestionGenRecoveryCount(@Param("sessionId") String sessionId);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.questionRecoveryCount = 0 "
        + "WHERE s.sessionId = :sessionId")
    int resetQuestionGenRecoveryCount(@Param("sessionId") String sessionId);

    @Query("SELECT s FROM PracticeSessionEntity s WHERE s.questionStatus = :status "
        + "AND s.questionUpdatedAt < :threshold ORDER BY s.questionUpdatedAt ASC")
    List<PracticeSessionEntity> findStaleByQuestionStatus(
        @Param("status") AsyncTaskStatus status,
        @Param("threshold") LocalDateTime threshold,
        Pageable pageable);

    // ========== 批改任务条件领取与终态写入 ==========

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.evaluateStatus = 'PENDING', "
        + "s.evaluateError = null WHERE s.sessionId = :sessionId "
        + "AND (s.evaluateStatus IS NULL OR s.evaluateStatus = 'FAILED' "
        + "OR s.evaluateStatus = 'PENDING')")
    int tryMarkEvaluatePendingIfIdle(@Param("sessionId") String sessionId);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.evaluateStatus = 'PROCESSING', "
        + "s.evaluateError = null WHERE s.sessionId = :sessionId "
        + "AND s.evaluateStatus = 'PENDING'")
    int tryMarkEvaluateProcessing(@Param("sessionId") String sessionId);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.evaluateStatus = 'PROCESSING', "
        + "s.evaluateAttemptId = :attemptId, s.evaluateHeartbeatAt = :now, "
        + "s.evaluateError = null WHERE s.sessionId = :sessionId "
        + "AND s.evaluateStatus = 'PENDING'")
    int tryMarkEvaluateProcessing(@Param("sessionId") String sessionId,
                                  @Param("attemptId") String attemptId,
                                  @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.evaluateHeartbeatAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.evaluateStatus = 'PROCESSING' "
        + "AND s.evaluateAttemptId = :attemptId")
    int heartbeatEvaluateProcessing(@Param("sessionId") String sessionId,
                                    @Param("attemptId") String attemptId,
                                    @Param("now") LocalDateTime now);

    @Query("SELECT s FROM PracticeSessionEntity s WHERE s.evaluateStatus = 'PROCESSING' "
        + "AND (s.evaluateHeartbeatAt IS NULL OR s.evaluateHeartbeatAt < :threshold) "
        + "ORDER BY s.evaluateHeartbeatAt ASC")
    List<PracticeSessionEntity> findStaleEvaluateProcessing(
        @Param("threshold") LocalDateTime threshold, Pageable pageable);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.evaluateStatus = 'PENDING', "
        + "s.evaluateAttemptId = null WHERE s.sessionId = :sessionId "
        + "AND s.evaluateStatus = 'PROCESSING' "
        + "AND (s.evaluateHeartbeatAt IS NULL OR s.evaluateHeartbeatAt < :threshold)")
    int resetStaleEvaluateProcessing(@Param("sessionId") String sessionId,
                                     @Param("threshold") LocalDateTime threshold);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.evaluateStatus = 'COMPLETED', "
        + "s.evaluateError = null WHERE s.sessionId = :sessionId "
        + "AND s.evaluateStatus = 'PROCESSING'")
    int completeEvaluateIfProcessing(@Param("sessionId") String sessionId);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.evaluateStatus = 'FAILED', "
        + "s.evaluateError = :error WHERE s.sessionId = :sessionId "
        + "AND s.evaluateStatus = 'PROCESSING'")
    int failEvaluateIfProcessing(@Param("sessionId") String sessionId,
                                 @Param("error") String error);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.evaluateStatus = 'FAILED', "
        + "s.evaluateError = :error WHERE s.sessionId = :sessionId "
        + "AND s.evaluateStatus = 'PENDING'")
    int failEvaluateIfPending(@Param("sessionId") String sessionId,
                              @Param("error") String error);

    @Transactional
    @Modifying
    @Query("UPDATE PracticeSessionEntity s SET s.evaluateStatus = 'PENDING', "
        + "s.evaluateError = null WHERE s.sessionId = :sessionId "
        + "AND s.evaluateStatus = 'PROCESSING'")
    int resetEvaluateToPending(@Param("sessionId") String sessionId);
}
