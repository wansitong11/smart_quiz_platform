package interview.guide.infrastructure.mapper;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.MessageDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagScopeKind;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * RAG 聊天实体 → DTO 映射。
 * <p>
 * 会话上多了「检索范围」这层语义（含分类展示名），映射时把范围描述一并算好，
 * 前端不必再自己拼「全部资料 / 分类：数学 / 3 份指定资料」。
 * </p>
 */
@Component
public class RagChatMapper {

    public SessionDTO toSessionDTO(RagChatSessionEntity session, Map<String, String> categoryNames) {
        return new SessionDTO(
            session.getId(),
            session.getTitle(),
            session.scopeKind().name(),
            session.getKbCategory(),
            scopeDescription(session, categoryNames),
            session.getKnowledgeBaseIds(),
            session.getCreatedAt()
        );
    }

    public SessionListItemDTO toSessionListItemDTO(RagChatSessionEntity session,
                                                   Map<String, String> categoryNames) {
        return new SessionListItemDTO(
            session.getId(),
            session.getTitle(),
            session.getMessageCount() != null ? session.getMessageCount() : 0,
            session.scopeKind().name(),
            session.getKbCategory(),
            scopeDescription(session, categoryNames),
            session.getKnowledgeBases().stream().map(kb -> kb.getName()).toList(),
            session.getUpdatedAt(),
            session.getIsPinned() != null ? session.getIsPinned() : false
        );
    }

    public SessionDetailDTO toSessionDetailDTO(RagChatSessionEntity session,
                                               List<RagChatMessageEntity> messages,
                                               List<KnowledgeBaseListItemDTO> knowledgeBases,
                                               Map<String, String> categoryNames) {
        return new SessionDetailDTO(
            session.getId(),
            session.getTitle(),
            session.scopeKind().name(),
            session.getKbCategory(),
            scopeDescription(session, categoryNames),
            knowledgeBases,
            toMessageDTOList(messages),
            session.getCreatedAt(),
            session.getUpdatedAt()
        );
    }

    public MessageDTO toMessageDTO(RagChatMessageEntity message) {
        return new MessageDTO(
            message.getId(),
            message.getTypeString(),
            message.getContent(),
            message.getCreatedAt()
        );
    }

    public List<MessageDTO> toMessageDTOList(List<RagChatMessageEntity> messages) {
        return messages.stream().map(this::toMessageDTO).toList();
    }

    /**
     * 范围描述：不需要额外查库（分类名来自调用方批量查出的映射）。
     */
    private String scopeDescription(RagChatSessionEntity session, Map<String, String> categoryNames) {
        RagScopeKind kind = session.scopeKind();
        return switch (kind) {
            case ALL -> "全部资料";
            case CATEGORY -> {
                String code = session.getKbCategory();
                String name = code != null && categoryNames != null ? categoryNames.get(code) : null;
                yield "分类：" + (name != null ? name : code);
            }
            case SELECTED -> {
                int size = session.getKnowledgeBases().size();
                if (size == 0) {
                    yield "未选择资料";
                }
                yield size == 1
                    ? "资料：" + session.getKnowledgeBases().iterator().next().getName()
                    : size + " 份指定资料";
            }
        };
    }
}
