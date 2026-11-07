package interview.guide.modules.quiz.repository;

import interview.guide.modules.quiz.model.WrongQuestionBackfillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WrongQuestionBackfillRepository
    extends JpaRepository<WrongQuestionBackfillEntity, Long> {
}
