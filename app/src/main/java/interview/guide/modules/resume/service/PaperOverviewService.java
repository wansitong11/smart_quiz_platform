package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.resume.model.PaperOverviewDTO;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.RecognitionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 试卷概览服务：从已缓存的切题结构生成只读预览，不创建作答会话。
 */
@Service
@RequiredArgsConstructor
public class PaperOverviewService {

    private final ResumePersistenceService resumePersistenceService;
    private final PaperStructureService structureService;

    public PaperOverviewDTO overview(Long paperId) {
        PracticePaperEntity paper = resumePersistenceService.findById(paperId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "试卷不存在或无权访问"));
        RecognitionStatus recognition = paper.getRecognitionStatus();
        if (recognition != null
            && recognition != RecognitionStatus.NONE
            && recognition != RecognitionStatus.READY) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST, "试卷仍在识别或等待校对，暂不可预览");
        }
        if (paper.getAnalysisStatus() != AsyncTaskStatus.COMPLETED) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST, "试卷切题完成后才能预览");
        }

        PaperStructureService.BuiltStructure built = structureService.buildCached(paperId);
        PaperTree studentTree = built.paperTree().forOverviewView();
        return new PaperOverviewDTO(
            paperId,
            built.subjectId(),
            built.totalScore(),
            studentTree.flattenQuestions().size(),
            studentTree
        );
    }
}
