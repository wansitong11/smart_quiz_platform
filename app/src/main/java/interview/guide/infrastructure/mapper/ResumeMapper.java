package interview.guide.infrastructure.mapper;

import interview.guide.modules.resume.model.PaperAnalysisEntity;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import org.mapstruct.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 试卷 / 练习材料相关的对象映射器
 * 使用MapStruct自动生成转换代码
 * <p>
 * 注意：JSON字段(topicsJson, warningsJson)需要在Service层手动处理
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ResumeMapper {

    // ========== ResumeListItemDTO 映射 ==========

    /**
     * PracticePaperEntity 转换为 ResumeListItemDTO
     * 需要额外传入 latestScore, lastAnalyzedAt, interviewCount
     */
    default ResumeListItemDTO toListItemDTO(
        PracticePaperEntity resume,
        Integer latestScore,
        LocalDateTime lastAnalyzedAt,
        Integer interviewCount
    ) {
        return new ResumeListItemDTO(
            resume.getId(),
            resume.getOriginalFilename(),
            resume.getFileSize(),
            resume.getUploadedAt(),
            resume.getAccessCount(),
            latestScore,
            lastAnalyzedAt,
            interviewCount,
            null,
            null,
            null
        );
    }

    /**
     * 简化版：从 PracticePaperEntity 直接映射（其他字段为 null）
     */
    @Mapping(target = "filename", source = "originalFilename")
    @Mapping(target = "latestScore", ignore = true)
    @Mapping(target = "lastAnalyzedAt", ignore = true)
    @Mapping(target = "interviewCount", ignore = true)
    ResumeListItemDTO toListItemDTOBasic(PracticePaperEntity entity);

    // ========== ResumeDetailDTO 映射 ==========

    /**
     * PracticePaperEntity 转换为 ResumeDetailDTO（不含 analyses 和 interviews）
     */
    @Mapping(target = "filename", source = "originalFilename")
    @Mapping(target = "analyses", ignore = true)
    @Mapping(target = "interviews", ignore = true)
    ResumeDetailDTO toDetailDTOBasic(PracticePaperEntity entity);

    // ========== AnalysisHistoryDTO 映射 ==========

    /**
     * PaperAnalysisEntity 转换为 AnalysisHistoryDTO
     * 注意：topics 和 warnings 需要在 Service 层从 JSON 解析后传入
     */
    default ResumeDetailDTO.AnalysisHistoryDTO toAnalysisHistoryDTO(
        PaperAnalysisEntity entity,
        List<String> topics,
        List<String> warnings
    ) {
        return new ResumeDetailDTO.AnalysisHistoryDTO(
            entity.getId(),
            entity.getQuestionCount(),
            entity.getTotalScore(),
            entity.getSummary(),
            entity.getAnalyzedAt(),
            entity.getPracticeSessionId(),
            topics,
            warnings
        );
    }
}
