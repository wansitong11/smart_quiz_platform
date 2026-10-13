package interview.guide.modules.knowledgebase.service.scope;

import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagScopeKind;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.auth.security.OwnerScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 全部资料范围：不做过滤，检索所有已完成向量化的资料。
 */
@Component
@RequiredArgsConstructor
public class AllScopeResolver implements RagScopeResolver {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final OwnerScope ownerScope;

    @Override
    public RagScopeKind kind() {
        return RagScopeKind.ALL;
    }

    @Override
    public ResolvedScope resolve(RagChatSessionEntity session) {
        // 关键：必须显式给出"我能看到的资料 ID"，不能返回空集合 ——
        // 空集合在检索层等价于"不过滤"，会把别人的资料一起召回
        Long ownerId = ownerScope.effectiveOwnerId();
        List<Long> ids = knowledgeBaseRepository
            .findAccessibleIdsByVectorStatus(ownerId, VectorStatus.COMPLETED);
        long total = knowledgeBaseRepository.findAllAccessible(ownerId).size();
        return new ResolvedScope(RagScopeKind.ALL, ids, "全部资料",
            Math.max(0, total - ids.size()));
    }
}
