package interview.guide.modules.aicost.repository;

import interview.guide.modules.aicost.model.AiCallEventEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiCallEventRepository extends JpaRepository<AiCallEventEntity, Long> {

  List<AiCallEventEntity> findByUserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
      Long userId, Instant from, Instant to);
}
