package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewHistoryItemDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历详情DTO
 */
public record ResumeDetailDTO(
    Long id,
    String filename,
    Long fileSize,
    String contentType,
    String storageUrl,
    LocalDateTime uploadedAt,
    Integer accessCount,
    String resumeText,
    AsyncTaskStatus analysisStatus,
    String analysisError,
    RecognitionStatus recognitionStatus,
    String recognitionError,
    Integer pageCount,
    List<AnalysisHistoryDTO> analyses,
    List<InterviewHistoryItemDTO> interviews
) {
    /**
     * 试卷解析摘要 DTO（切题结果概览）
     */
    public record AnalysisHistoryDTO(
        Long id,
        Integer questionCount,
        Integer totalScore,
        String summary,
        LocalDateTime analyzedAt,
        String practiceSessionId,
        List<String> topics,
        List<String> warnings
    ) {}
}

