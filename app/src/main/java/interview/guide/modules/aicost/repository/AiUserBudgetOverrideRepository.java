package interview.guide.modules.aicost.repository;

import interview.guide.modules.aicost.model.AiUserBudgetOverrideEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiUserBudgetOverrideRepository
    extends JpaRepository<AiUserBudgetOverrideEntity, Long> {

  @Query("""
      select o from AiUserBudgetOverrideEntity o
      where o.userId = :userId
        and (o.feature is null or o.feature = :feature)
        and o.effectiveFrom <= :now and (o.effectiveTo is null or o.effectiveTo > :now)
      """)
  List<AiUserBudgetOverrideEntity> findActive(
      @Param("userId") Long userId,
      @Param("feature") String feature,
      @Param("now") Instant now);

  @Query("""
      select o from AiUserBudgetOverrideEntity o
      where o.userId = :userId
        and o.feature = :feature
        and o.effectiveFrom <= :now and (o.effectiveTo is null or o.effectiveTo > :now)
      """)
  List<AiUserBudgetOverrideEntity> findActiveFeature(
      @Param("userId") Long userId,
      @Param("feature") String feature,
      @Param("now") Instant now);

  @Query("""
      select o from AiUserBudgetOverrideEntity o
      where o.userId = :userId
        and o.feature is null
        and o.effectiveFrom <= :now and (o.effectiveTo is null or o.effectiveTo > :now)
      """)
  List<AiUserBudgetOverrideEntity> findActiveGlobal(
      @Param("userId") Long userId,
      @Param("now") Instant now);

  @Query("""
      select o from AiUserBudgetOverrideEntity o
      where o.userId = :userId
        and o.effectiveFrom <= :now and (o.effectiveTo is null or o.effectiveTo > :now)
      """)
  List<AiUserBudgetOverrideEntity> findActiveByUser(
      @Param("userId") Long userId,
      @Param("now") Instant now);

  @Query("""
      select o from AiUserBudgetOverrideEntity o
      where o.effectiveFrom <= :now and (o.effectiveTo is null or o.effectiveTo > :now)
      """)
  List<AiUserBudgetOverrideEntity> findAllActive(@Param("now") Instant now);
}
