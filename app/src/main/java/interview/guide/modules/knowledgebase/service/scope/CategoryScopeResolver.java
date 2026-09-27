package interview.guide.modules.knowledgebase.service.scope;

import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagScopeKind;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KbCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按分类检索：取该分类下已完成向量化的资料。
 */
@Component
@RequiredArgsConstructor
public class CategoryScopeResolver implements RagScopeResolver {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbCategoryService categoryService;

    @Override
    public RagScopeKind kind() {
        return RagScopeKind.CATEGORY;
    }

    @Override
    public ResolvedScope resolve(RagChatSessionEntity session) {
        String code = session.getKbCategory();
        if (code == null || code.isBlank()) {
            return new ResolvedScope(RagScopeKind.CATEGORY, List.of(), "未选择分类", 0);
        }

        List<Long> ids = knowledgeBaseRepository
            .findIdsByCategoryCodeAndVectorStatus(code, VectorStatus.COMPLETED);
        long total = knowledgeBaseRepository.countByCategoryCode(code);
        String name = categoryService.nameOf(code);
        return new ResolvedScope(RagScopeKind.CATEGORY, ids,
            "分类：" + (name != null ? name : code), Math.max(0, total - ids.size()));
    }
}
