package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.PaperInputFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface PaperInputFileRepository extends JpaRepository<PaperInputFileEntity, Long> {

    List<PaperInputFileEntity> findByPaperIdOrderBySeqAsc(Long paperId);

    @Transactional
    @Modifying
    @Query("DELETE FROM PaperInputFileEntity f WHERE f.paperId = :paperId")
    void deleteByPaperId(@Param("paperId") Long paperId);
}
