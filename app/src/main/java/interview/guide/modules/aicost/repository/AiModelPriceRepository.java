package interview.guide.modules.aicost.repository;

import interview.guide.modules.aicost.model.AiModelPriceEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiModelPriceRepository extends JpaRepository<AiModelPriceEntity, Long> {

  @Query("""
      select p from AiModelPriceEntity p
      where p.provider = :provider and p.model = :model and p.modality = :modality
        and p.effectiveFrom <= :now and (p.effectiveTo is null or p.effectiveTo > :now)
      order by case when p.source = interview.guide.modules.aicost.PriceSource.MANUAL then 0 else 1 end,
               p.effectiveFrom desc
      """)
  List<AiModelPriceEntity> findCurrent(
      @Param("provider") String provider,
      @Param("model") String model,
      @Param("modality") String modality,
      @Param("now") Instant now,
      Pageable pageable);

  @Query("""
      select p from AiModelPriceEntity p
      where p.effectiveTo is null or p.effectiveTo > :now
      order by p.provider, p.model, p.modality, p.effectiveFrom desc
      """)
  List<AiModelPriceEntity> findActive(@Param("now") Instant now);

  @Query("""
      select p from AiModelPriceEntity p
      where p.provider = :provider and p.model = :model and p.modality = :modality
        and p.source = interview.guide.modules.aicost.PriceSource.MANUAL
        and p.effectiveFrom <= :now and (p.effectiveTo is null or p.effectiveTo > :now)
      """)
  List<AiModelPriceEntity> findActiveManual(
      @Param("provider") String provider,
      @Param("model") String model,
      @Param("modality") String modality,
      @Param("now") Instant now);

  @Query("""
      select p from AiModelPriceEntity p
      where p.provider = :provider and p.model = :model and p.modality = :modality
        and p.source = interview.guide.modules.aicost.PriceSource.AUTO
        and (p.effectiveTo is null or p.effectiveTo > :now)
      """)
  List<AiModelPriceEntity> findActiveAuto(
      @Param("provider") String provider,
      @Param("model") String model,
      @Param("modality") String modality,
      @Param("now") Instant now);
}
