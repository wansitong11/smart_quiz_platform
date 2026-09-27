package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI {@link ChatMemoryRepository} 适配器：把「最近若干轮对话」交给框架的
 * {@code MessageChatMemoryAdvisor} 自动注入，不再手写加载逻辑。
 * <p>
 * 几点刻意的取舍：
 * <ul>
 *   <li><b>只读</b>：消息由 {@code RagChatSessionService} 写入 {@code rag_chat_messages}
 *       （它还要负责 SSE 占位消息的更新），如果这里再接受 {@code saveAll}，
 *       同一轮对话会落两份。所以写操作是空实现，框架只负责「读 + 注入」。</li>
 *   <li><b>去掉当前这一轮的提问</b>：那一轮的 user 消息在流式结束后才补上回答，
 *       而当前提问已经作为 prompt 发出去了，重复注入会让模型看到两遍。</li>
 *   <li><b>自己裁剪窗口</b>：{@code MessageWindowChatMemory} 只在 {@code add()} 时裁剪，
 *       读取时只是原样返回仓储内容；仓储只读时框架的 maxMessages 就不生效了，
 *       所以这里按窗口大小取最近 N 条，并把窗口起点对齐到一条 user 消息
 *       （与框架 process() 的策略一致，避免只带进半轮对话）。</li>
 *   <li>conversationId 就是会话 ID（字符串形式）。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Repository("ragChatMemoryRepository")
@RequiredArgsConstructor
public class RagChatMemoryRepository implements ChatMemoryRepository {

    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseQueryProperties properties;

    @Override
    public List<String> findConversationIds() {
        // 会话枚举由业务侧管理，这里不需要
        return List.of();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Long sessionId = parseSessionId(conversationId);
        if (sessionId == null) {
            return List.of();
        }

        int maxMessages = Math.max(1, properties.getTools().getChatMemoryMaxMessages());
        // 只取「最近的 maxMessages + 1 条」：多出来的一条用于剔除当前提问，
        // 避免把整个会话的历史都从数据库捞出来再在内存里裁剪。
        List<RagChatMessageEntity> recentDesc = messageRepository.findRecentCompletedBySessionId(
            sessionId, PageRequest.of(0, maxMessages + 1));
        if (recentDesc.isEmpty()) {
            return List.of();
        }

        boolean trimmed = recentDesc.size() > maxMessages;
        List<RagChatMessageEntity> history = new ArrayList<>(recentDesc);
        java.util.Collections.reverse(history);   // 查询是倒序，转成时间正序

        // 最新的这条 user 消息是当前这轮的提问：它已经作为 prompt 发出，别再注入一次
        if (!history.isEmpty()
            && history.getLast().getType() == RagChatMessageEntity.MessageType.USER) {
            history.removeLast();
        }
        // 窗口被裁剪时，起点对齐到一条 user 消息，避免只带进半轮对话
        if (trimmed) {
            while (!history.isEmpty()
                && history.getFirst().getType() != RagChatMessageEntity.MessageType.USER) {
                history.removeFirst();
            }
        }

        return history.stream()
            .map(this::toMessage)
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // 消息落库由 RagChatSessionService 负责，避免重复写入
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        // 会话删除由 RagChatSessionService.deleteSession 连带处理
    }

    private Message toMessage(RagChatMessageEntity entity) {
        String content = entity.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        return entity.getType() == RagChatMessageEntity.MessageType.USER
            ? new UserMessage(content)
            : new AssistantMessage(content);
    }

    private Long parseSessionId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(conversationId.trim());
        } catch (NumberFormatException e) {
            log.warn("非法会话 ID，跳过对话记忆注入: {}", conversationId);
            return null;
        }
    }
}
