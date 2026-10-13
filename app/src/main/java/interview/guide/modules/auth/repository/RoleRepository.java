package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.RoleEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<RoleEntity, Long> {

  @EntityGraph(attributePaths = {"permissions"})
  Optional<RoleEntity> findWithPermissionsByCode(String code);

  Optional<RoleEntity> findByCode(String code);
}
