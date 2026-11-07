package interview.guide.modules.quiz.repository;

import interview.guide.modules.quiz.model.WrongQuestionEntity;
import interview.guide.modules.quiz.model.WrongQuestionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 错题本仓储。列表查询由 Specification 组合筛选条件。
 */
@Repository
public interface WrongQuestionRepository extends JpaRepository<WrongQuestionEntity, Long>,
    JpaSpecificationExecutor<WrongQuestionEntity> {

  Optional<WrongQuestionEntity> findByOwnerIdAndQuestionKey(Long ownerId, String questionKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT w FROM WrongQuestionEntity w WHERE w.ownerId = :ownerId AND w.questionKey = :questionKey")
  Optional<WrongQuestionEntity> findForUpdate(@Param("ownerId") Long ownerId,
                                               @Param("questionKey") String questionKey);

  List<WrongQuestionEntity> findByOwnerIdAndQuestionKeyIn(Long ownerId,
                                                           Collection<String> questionKeys);

  List<WrongQuestionEntity> findByOwnerId(Long ownerId);

  long countByOwnerId(Long ownerId);

  long countByOwnerIdAndStatus(Long ownerId, WrongQuestionStatus status);

  @Query("""
      SELECT w.status, COUNT(w)
      FROM WrongQuestionEntity w
      WHERE w.ownerId = :ownerId
      GROUP BY w.status
      """)
  List<Object[]> countByStatus(@Param("ownerId") Long ownerId);

  @Query("""
      SELECT w.subjectName, COUNT(w)
      FROM WrongQuestionEntity w
      WHERE w.ownerId = :ownerId
      GROUP BY w.subjectName
      """)
  List<Object[]> countBySubject(@Param("ownerId") Long ownerId);
}
