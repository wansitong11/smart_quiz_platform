package interview.guide.modules.quiz.repository;

import interview.guide.modules.quiz.model.WrongQuestionAttemptEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WrongQuestionAttemptEventRepository
    extends JpaRepository<WrongQuestionAttemptEventEntity, Long> {

  boolean existsBySessionIdAndQuestionIndex(String sessionId, Integer questionIndex);
}
