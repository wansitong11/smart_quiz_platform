package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 知识库查询请求
 *
 * @param knowledgeBaseIds 参与检索的资料；为空表示检索全部资料
 */
public record QueryRequest(
    List<Long> knowledgeBaseIds,

    @NotBlank(message = "问题不能为空")
    String question
) {
    /**
     * 兼容单知识库查询（向后兼容）
     */
    public QueryRequest(Long knowledgeBaseId, String question) {
        this(List.of(knowledgeBaseId), question);
    }
}

