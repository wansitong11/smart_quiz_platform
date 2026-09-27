package interview.guide.modules.knowledgebase.service.scope;

import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagScopeKind;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
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

    @Override
    public RagScopeKind kind() {
        return RagScopeKind.ALL;
    }

    @Override
    public ResolvedScope resolve(RagChatSessionEntity session) {
        long total = knowledgeBaseRepository.count();
        long ready = knowledgeBaseRepository.countByVectorStatus(VectorStatus.COMPLETED);
        return new ResolvedScope(RagScopeKind.ALL, List.of(), "全部资料", Math.max(0, total - ready));
    }
}
