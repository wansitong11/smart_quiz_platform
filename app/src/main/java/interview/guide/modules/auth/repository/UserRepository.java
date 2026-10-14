package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

  @EntityGraph(attributePaths = {"roles", "roles.permissions"})
  Optional<UserEntity> findWithRolesByUsername(String username);

  @EntityGraph(attributePaths = {"roles", "roles.permissions"})
  Optional<UserEntity> findWithRolesById(Long id);

  boolean existsByUsername(String username);

  /** 某个来源 IP 在指定时间之后成功自助注册的账号数（注册配额控制用） */
  @Query("SELECT COUNT(u) FROM UserEntity u WHERE u.registeredIp = :ip AND u.createdAt >= :since")
  long countSelfRegisteredByIpSince(@Param("ip") String ip, @Param("since") LocalDateTime since);

  /** 全站自指定时间之后成功自助注册的账号数（只算自助注册，不含管理员建号） */
  @Query("SELECT COUNT(u) FROM UserEntity u WHERE u.registeredIp IS NOT NULL AND u.createdAt >= :since")
  long countSelfRegisteredSince(@Param("since") LocalDateTime since);
}
