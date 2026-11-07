package interview.guide.modules.aicost.repository;

import interview.guide.modules.aicost.model.AiUserRateLimitOverrideEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiUserRateLimitOverrideRepository
    extends JpaRepository<AiUserRateLimitOverrideEntity, Long> {

  @Query("""
      select o from AiUserRateLimitOverrideEntity o
      where o.userId = :userId
        and o.feature = :feature
        and o.effectiveFrom <= :now and (o.effectiveTo is null or o.effectiveTo > :now)
      """)
  List<AiUserRateLimitOverrideEntity> findActiveFeature(
      @Param("userId") Long userId,
      @Param("feature") String feature,
      @Param("now") Instant now);

  @Query("""
      select o from AiUserRateLimitOverrideEntity o
      where o.userId = :userId
        and o.feature is null
        and o.effectiveFrom <= :now and (o.effectiveTo is null or o.effectiveTo > :now)
      """)
  List<AiUserRateLimitOverrideEntity> findActiveGlobal(
      @Param("userId") Long userId,
      @Param("now") Instant now);

  @Query("""
      select o from AiUserRateLimitOverrideEntity o
      where o.userId = :userId
        and o.effectiveFrom <= :now and (o.effectiveTo is null or o.effectiveTo > :now)
      """)
  List<AiUserRateLimitOverrideEntity> findActiveByUser(
      @Param("userId") Long userId,
      @Param("now") Instant now);

  @Query("""
      select o from AiUserRateLimitOverrideEntity o
      where o.effectiveFrom <= :now and (o.effectiveTo is null or o.effectiveTo > :now)
      """)
  List<AiUserRateLimitOverrideEntity> findAllActive(@Param("now") Instant now);
}
