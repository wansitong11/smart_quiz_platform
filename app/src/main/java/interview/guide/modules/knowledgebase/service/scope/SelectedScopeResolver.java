package interview.guide.modules.knowledgebase.service.scope;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagScopeKind;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指定资料范围：只检索会话勾选的资料。
 */
@Component
public class SelectedScopeResolver implements RagScopeResolver {

    @Override
    public RagScopeKind kind() {
        return RagScopeKind.SELECTED;
    }

    @Override
    public ResolvedScope resolve(RagChatSessionEntity session) {
        List<KnowledgeBaseEntity> selected = List.copyOf(session.getKnowledgeBases());
        List<Long> ids = selected.stream()
            .filter(kb -> VectorStatus.COMPLETED.equals(kb.getVectorStatus()))
            .map(KnowledgeBaseEntity::getId)
            .toList();

        long unavailable = selected.size() - ids.size();
        String description;
        if (selected.isEmpty()) {
            description = "未选择资料";
        } else if (selected.size() == 1) {
            description = "资料：" + selected.getFirst().getName();
        } else {
            description = selected.size() + " 份指定资料";
        }
        return new ResolvedScope(RagScopeKind.SELECTED, ids, description, unavailable);
    }
}
