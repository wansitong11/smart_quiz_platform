package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG聊天相关DTO
 */
public class RagChatDTO {

    // ========== 请求 DTO ==========

    /**
     * 创建会话请求
     * <p>
     * scope 缺省时按 knowledgeBaseIds 推断：非空 → SELECTED，空 → ALL（全部资料）。
     *
     * @param scope            检索范围：ALL / CATEGORY / SELECTED
     * @param categoryCode     scope = CATEGORY 时的分类 code
     * @param knowledgeBaseIds scope = SELECTED 时勾选的资料
     * @param title            会话标题，可空（自动生成）
     */
    public record CreateSessionRequest(
        String scope,
        String categoryCode,
        List<Long> knowledgeBaseIds,

        String title  // 可选，为空则自动生成
    ) {}

    /**
     * 发送消息请求
     */
    public record SendMessageRequest(
        @NotBlank(message = "问题不能为空")
        String question
    ) {}

    /**
     * 更新标题请求
     */
    public record UpdateTitleRequest(
        @NotBlank(message = "标题不能为空")
        String title
    ) {}

    /**
     * 更新检索范围请求
     */
    public record UpdateScopeRequest(
        String scope,
        String categoryCode,
        List<Long> knowledgeBaseIds
    ) {}

    // ========== 响应 DTO ==========

    /**
     * 会话基础信息
     */
    public record SessionDTO(
        Long id,
        String title,
        String scope,
        String categoryCode,
        String scopeDescription,
        List<Long> knowledgeBaseIds,
        LocalDateTime createdAt
    ) {}

    /**
     * 会话列表项
     */
    public record SessionListItemDTO(
        Long id,
        String title,
        Integer messageCount,
        String scope,
        String categoryCode,
        String scopeDescription,
        List<String> knowledgeBaseNames,
        LocalDateTime updatedAt,
        Boolean isPinned
    ) {}

    /**
     * 会话详情（含消息）
     */
    public record SessionDetailDTO(
        Long id,
        String title,
        String scope,
        String categoryCode,
        String scopeDescription,
        List<KnowledgeBaseListItemDTO> knowledgeBases,
        List<MessageDTO> messages,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    /**
     * 消息 DTO
     */
    public record MessageDTO(
        Long id,
        String type,  // "user" | "assistant"
        String content,
        LocalDateTime createdAt
    ) {}
}
