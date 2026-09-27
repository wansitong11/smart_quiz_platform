package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建资料分类请求。
 *
 * @param name        展示名（必填，如「物理」）
 * @param code        分类编码，可空；为空时服务端按名称生成
 * @param subjectCode 关联学科 key，可空
 * @param sortOrder   排序值，可空
 */
public record CreateKbCategoryRequest(
    @NotBlank(message = "分类名称不能为空")
    String name,
    String code,
    String subjectCode,
    Integer sortOrder
) {
}
