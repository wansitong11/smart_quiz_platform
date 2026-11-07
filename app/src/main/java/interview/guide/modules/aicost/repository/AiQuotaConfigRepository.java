package interview.guide.modules.aicost.repository;

import interview.guide.modules.aicost.model.AiQuotaConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiQuotaConfigRepository extends JpaRepository<AiQuotaConfigEntity, Long> {
}
