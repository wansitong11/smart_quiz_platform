package interview.guide.infrastructure.mapper;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库实体到 DTO 的映射器。
 * <p>
 * 分类需要由 code 反查展示名（来源是分类表，不在实体上），
 * 所以不做 MapStruct 的隐式映射，改成显式代码 + 一个批量名称映射。
 * </p>
 */
@Component
public class KnowledgeBaseMapper {

    /**
     * 将知识库实体转换为列表项DTO
     *
     * @param entity        资料实体
     * @param categoryNames 分类 code → 展示名（可为空表示全部未知）
     * @param ownerNames    用户 id → 账号名（可为空表示不展示上传人）
     */
    public KnowledgeBaseListItemDTO toListItemDTO(KnowledgeBaseEntity entity,
                                                  Map<String, String> categoryNames,
                                                  Map<Long, String> ownerNames) {
        String code = entity.getCategoryCode();
        String name = code != null && categoryNames != null ? categoryNames.get(code) : null;
        String ownerName = entity.getOwnerId() != null && ownerNames != null
            ? ownerNames.get(entity.getOwnerId())
            : null;
        return new KnowledgeBaseListItemDTO(
            entity.getId(),
            entity.getName(),
            name,
            code,
            entity.getOriginalFilename(),
            entity.getFileSize(),
            entity.getContentType(),
            entity.getUploadedAt(),
            entity.getLastAccessedAt(),
            entity.getAccessCount(),
            entity.getQuestionCount(),
            entity.getVectorStatus(),
            entity.getVectorError(),
            entity.getChunkCount(),
            entity.getQuestionGenStatus(),
            entity.getQuestionGenError(),
            entity.getVisibility(),
            entity.getOwnerId(),
            ownerName
        );
    }

    public KnowledgeBaseListItemDTO toListItemDTO(KnowledgeBaseEntity entity,
                                                  Map<String, String> categoryNames) {
        return toListItemDTO(entity, categoryNames, Map.of());
    }

    public KnowledgeBaseListItemDTO toListItemDTO(KnowledgeBaseEntity entity) {
        return toListItemDTO(entity, Map.of());
    }

    /**
     * 将知识库实体列表转换为列表项DTO列表
     */
    public List<KnowledgeBaseListItemDTO> toListItemDTOList(List<KnowledgeBaseEntity> entities,
                                                            Map<String, String> categoryNames) {
        return toListItemDTOList(entities, categoryNames, Map.of());
    }

    /**
     * 将知识库实体列表转换为列表项DTO列表（带上「用户 id → 账号名」批量映射）
     */
    public List<KnowledgeBaseListItemDTO> toListItemDTOList(List<KnowledgeBaseEntity> entities,
                                                            Map<String, String> categoryNames,
                                                            Map<Long, String> ownerNames) {
        return entities.stream()
            .map(entity -> toListItemDTO(entity, categoryNames, ownerNames))
            .toList();
    }

    public List<KnowledgeBaseListItemDTO> toListItemDTOList(List<KnowledgeBaseEntity> entities) {
        return toListItemDTOList(entities, Map.of());
    }
}

