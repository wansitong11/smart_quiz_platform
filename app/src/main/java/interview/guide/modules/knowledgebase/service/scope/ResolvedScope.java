package interview.guide.modules.knowledgebase.service.scope;

import interview.guide.modules.knowledgebase.model.RagScopeKind;

import java.util.List;

/**
 * 会话检索范围解析结果。
 * <p>
 * 检索层只认 {@link #knowledgeBaseIds()}：空列表表示「不加过滤 = 全部资料」
 * （对应 {@code KnowledgeBaseVectorService.similaritySearch} 的既有语义）。
 * </p>
 *
 * @param kind             范围类型
 * @param knowledgeBaseIds 参与检索的资料 ID；空表示全部
 * @param description      给界面看的范围描述，如「全部资料」「分类：数学」
 * @param unavailableCount 范围内尚未完成向量化的资料数（用于提示，不参与检索）
 */
public record ResolvedScope(
    RagScopeKind kind,
    List<Long> knowledgeBaseIds,
    String description,
    long unavailableCount
) {

    /** 范围解析后没有可用资料（ALL 不受此影响：全库为空会在检索后走拒答） */
    public boolean isEmptySelection() {
        return kind != RagScopeKind.ALL && knowledgeBaseIds.isEmpty();
    }

    /**
     * 直接用一组资料构造范围（老入口：单次查询接口、知识库出题等仍按 ID 调用）。
     * 传入空列表表示全部资料。
     */
    public static ResolvedScope ofSelection(List<Long> knowledgeBaseIds) {
        List<Long> ids = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        if (ids.isEmpty()) {
            return new ResolvedScope(RagScopeKind.ALL, List.of(), "全部资料", 0);
        }
        return new ResolvedScope(RagScopeKind.SELECTED, ids,
            ids.size() == 1 ? "1 份指定资料" : ids.size() + " 份指定资料", 0);
    }

    /** 未向量化提示文案，没有则返回空串 */
    public String unavailableHint() {
        return unavailableCount > 0
            ? "还有 " + unavailableCount + " 份资料未完成向量化，暂时不会被检索到"
            : "";
    }
}
