package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.UserModelOverrideEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserModelOverrideRepository
    extends JpaRepository<UserModelOverrideEntity, Long> {

  Optional<UserModelOverrideEntity> findByUserIdAndModality(Long userId, String modality);

  List<UserModelOverrideEntity> findAllByUserIdIn(Collection<Long> userIds);

  void deleteByUserIdAndModality(Long userId, String modality);
}
