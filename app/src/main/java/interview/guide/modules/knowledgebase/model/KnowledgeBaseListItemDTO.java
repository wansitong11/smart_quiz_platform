package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;

/**
 * 知识库列表项DTO
 * 使用MapStruct进行转换，见KnowledgeBaseMapper
 *
 * @param category     分类展示名（未分类时为 null，由 Mapper/Service 按 categoryCode 补名）
 * @param categoryCode 分类编码
 */
public record KnowledgeBaseListItemDTO(
    Long id,
    String name,
    String category,
    String categoryCode,
    String originalFilename,
    Long fileSize,
    String contentType,
    LocalDateTime uploadedAt,
    LocalDateTime lastAccessedAt,
    Integer accessCount,
    Integer questionCount,
    VectorStatus vectorStatus,
    String vectorError,
    Integer chunkCount,
    QuestionGenStatus questionGenStatus,
    String questionGenError
) {
}

