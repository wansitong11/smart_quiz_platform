package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.RefreshTokenEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

  Optional<RefreshTokenEntity> findByTokenId(String tokenId);

  List<RefreshTokenEntity> findByUserIdAndRevokedAtIsNull(Long userId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update RefreshTokenEntity t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
  int revokeAllByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

  @Modifying
  @Query("delete from RefreshTokenEntity t where t.expiresAt < :before")
  int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
