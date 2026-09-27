package interview.guide.modules.knowledgebase.model;

/**
 * 资料分类 DTO。
 *
 * @param id                分类 ID
 * @param code              分类编码（会话范围与资料关联使用）
 * @param name              展示名
 * @param subjectCode       关联的学科 key，可为空
 * @param sortOrder         排序值
 * @param isSystem          是否系统预置（不可删除）
 * @param knowledgeBaseCount 该分类下的资料数
 * @param pendingVectorCount 该分类下未完成向量化的资料数
 */
public record KbCategoryDTO(
    Long id,
    String code,
    String name,
    String subjectCode,
    Integer sortOrder,
    Boolean isSystem,
    long knowledgeBaseCount,
    long pendingVectorCount
) {

    /** 未分类资料的统计（不对应真实分类行，仅用于列表展示） */
    public static KbCategoryDTO uncategorized(long knowledgeBaseCount, long pendingVectorCount) {
        return new KbCategoryDTO(null, null, "未分类", null, Integer.MAX_VALUE, false,
            knowledgeBaseCount, pendingVectorCount);
    }
}
