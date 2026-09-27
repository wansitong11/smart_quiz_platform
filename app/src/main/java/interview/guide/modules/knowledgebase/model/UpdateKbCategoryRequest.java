package interview.guide.modules.knowledgebase.model;

/**
 * 更新资料分类请求：只允许改名 / 挂学科 / 排序，code 不可改。
 */
public record UpdateKbCategoryRequest(
    String name,
    String subjectCode,
    Integer sortOrder
) {
}
