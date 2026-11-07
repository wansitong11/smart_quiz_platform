package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.model.UserTier;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

  @EntityGraph(attributePaths = {"roles", "roles.permissions"})
  Optional<UserEntity> findWithRolesByUsername(String username);

  @EntityGraph(attributePaths = {"roles", "roles.permissions"})
  Optional<UserEntity> findWithRolesById(Long id);

  @EntityGraph(attributePaths = {"roles"})
  @Query("SELECT u FROM UserEntity u ORDER BY u.id")
  List<UserEntity> findAllWithRoles();

  @Query("""
      SELECT COUNT(u)
      FROM UserEntity u
      JOIN u.roles r
      WHERE u.id = :userId AND r.code = :roleCode
      """)
  long countByIdAndRoleCode(@Param("userId") Long userId, @Param("roleCode") String roleCode);

  boolean existsByUsername(String username);

  Page<UserEntity> findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
      String username, String displayName, Pageable pageable);

  /** 某个来源 IP 在指定时间之后成功自助注册的账号数（注册配额控制用） */
  @Query("SELECT COUNT(u) FROM UserEntity u WHERE u.registeredIp = :ip AND u.createdAt >= :since")
  long countSelfRegisteredByIpSince(@Param("ip") String ip, @Param("since") LocalDateTime since);

  /** 全站自指定时间之后成功自助注册的账号数（只算自助注册，不含管理员建号） */
  @Query("SELECT COUNT(u) FROM UserEntity u WHERE u.registeredIp IS NOT NULL AND u.createdAt >= :since")
  long countSelfRegisteredSince(@Param("since") LocalDateTime since);

  /** 免费档用户数（不含管理员、不含授权用户），用于月度总量池人均分配。 */
  @Query("""
      SELECT COUNT(u)
      FROM UserEntity u
      WHERE u.tier = :tier
        AND u.status = :status
        AND NOT EXISTS (SELECT 1 FROM u.roles r WHERE r.code = :adminRole)
      """)
  long countByTierAndStatusExcludingRole(
      @Param("tier") UserTier tier,
      @Param("status") UserStatus status,
      @Param("adminRole") String adminRole);

  @Query("SELECT u.tier FROM UserEntity u WHERE u.id = :id")
  Optional<UserTier> findTierById(@Param("id") Long id);
}
