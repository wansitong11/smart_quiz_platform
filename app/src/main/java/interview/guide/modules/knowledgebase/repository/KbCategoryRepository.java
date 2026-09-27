package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KbCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 资料分类 Repository
 */
@Repository
public interface KbCategoryRepository extends JpaRepository<KbCategoryEntity, Long> {

    List<KbCategoryEntity> findAllByOrderBySortOrderAscIdAsc();

    Optional<KbCategoryEntity> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByName(String name);
}
