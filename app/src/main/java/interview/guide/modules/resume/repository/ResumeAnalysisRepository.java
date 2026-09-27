package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.PaperAnalysisEntity;
import interview.guide.modules.resume.model.PracticePaperEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 简历评测Repository
 */
@Repository
public interface ResumeAnalysisRepository extends JpaRepository<PaperAnalysisEntity, Long> {
    
    /**
     * 根据简历查找所有评测记录
     */
    List<PaperAnalysisEntity> findByResumeOrderByAnalyzedAtDesc(PracticePaperEntity resume);
    
    /**
     * 根据简历ID查找最新评测记录
     */
    PaperAnalysisEntity findFirstByResumeIdOrderByAnalyzedAtDesc(Long resumeId);
    
    /**
     * 根据简历ID查找所有评测记录
     */
    List<PaperAnalysisEntity> findByResumeIdOrderByAnalyzedAtDesc(Long resumeId);
}
