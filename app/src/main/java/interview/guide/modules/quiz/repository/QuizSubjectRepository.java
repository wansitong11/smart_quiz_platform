package interview.guide.modules.quiz.repository;

import interview.guide.modules.quiz.model.QuizSubjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 自定义测验科目仓储。所有查询都按归属人过滤。
 */
@Repository
public interface QuizSubjectRepository extends JpaRepository<QuizSubjectEntity, Long> {

  List<QuizSubjectEntity> findByOwnerIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long ownerId);

  Optional<QuizSubjectEntity> findByOwnerIdAndSubjectCodeAndDeletedAtIsNull(
      Long ownerId, String subjectCode);
}
