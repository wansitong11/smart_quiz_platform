package interview.guide.modules.aicost.repository;

import interview.guide.modules.aicost.model.AiUsageOperationEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiUsageOperationRepository extends JpaRepository<AiUsageOperationEntity, String> {

  List<AiUsageOperationEntity> findByStatusAndStartedAtBefore(String status, Instant cutoff);
}
