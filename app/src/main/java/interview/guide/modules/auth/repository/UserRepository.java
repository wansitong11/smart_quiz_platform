package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

  @EntityGraph(attributePaths = {"roles", "roles.permissions"})
  Optional<UserEntity> findWithRolesByUsername(String username);

  @EntityGraph(attributePaths = {"roles", "roles.permissions"})
  Optional<UserEntity> findWithRolesById(Long id);

  boolean existsByUsername(String username);
}
