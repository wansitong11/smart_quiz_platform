package interview.guide.modules.knowledgebase.service.tool;

import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 读取一次会话的历史消息，供「会话元问题」与 {@code get_conversation_history} 工具使用。
 * <p>
 * 与旧的 {@code loadHistoryMessages} 的区别：这里是「完整、有序、可指定方向」的历史，
 * 支持 {@code ASC}（问「第一个问题」）与 {@code DESC}（问「我最近问了什么」）。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ConversationHistoryReader {

    /** 单条消息写入提示词时的最大长度，避免一条长回答吃掉上下文 */
    private static final int MAX_MESSAGE_CHARS = 600;

    private final RagChatMessageRepository messageRepository;

    /**
     * 读取历史消息。
     *
     * @param sessionId 会话 ID
     * @param limit     条数上限
     * @param ascending true = 从最早开始（问「第一个问题」），false = 从最新开始
     * @param excludeCurrentQuestion 是否排除当前这轮的提问（它刚被写入且还没回答）
     */
    public List<RagChatMessageEntity> read(Long sessionId, int limit, boolean ascending,
                                           boolean excludeCurrentQuestion) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        // 只按需要的方向取 limit+1 条（多一条用于剔除当前提问），不做全量加载
        int pageSize = limit + 1;
        List<RagChatMessageEntity> fetched = ascending
            ? messageRepository.findFirstCompletedBySessionId(sessionId, PageRequest.of(0, pageSize))
            : messageRepository.findRecentCompletedBySessionId(sessionId, PageRequest.of(0, pageSize));
        if (fetched.isEmpty()) {
            return List.of();
        }

        List<RagChatMessageEntity> ordered = new ArrayList<>(fetched);
        if (!ascending) {
            Collections.reverse(ordered);   // 倒序查询结果转成正序
        }

        // 只有查询已经覆盖到会话末尾时，最后一条才可能是「当前提问」
        boolean reachedEnd = fetched.size() < pageSize;
        if (excludeCurrentQuestion && reachedEnd && !ordered.isEmpty()
            && ordered.getLast().getType() == RagChatMessageEntity.MessageType.USER) {
            ordered.removeLast();
        }
        if (ordered.isEmpty()) {
            return List.of();
        }
        if (ascending) {
            return List.copyOf(ordered.subList(0, Math.min(limit, ordered.size())));
        }
        return List.copyOf(ordered.subList(Math.max(0, ordered.size() - limit), ordered.size()));
    }

    /**
     * 渲染成给模型看的文本（带序号，方便回答「第几个问题」）。
     */
    public String render(List<RagChatMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            RagChatMessageEntity message = messages.get(i);
            String role = message.getType() == RagChatMessageEntity.MessageType.USER ? "用户" : "助手";
            sb.append('[').append(i + 1).append("] ").append(role).append("：")
                .append(truncate(message.getContent())).append('\n');
        }
        return sb.toString().trim();
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        return trimmed.length() <= MAX_MESSAGE_CHARS
            ? trimmed
            : trimmed.substring(0, MAX_MESSAGE_CHARS) + "…（已截断）";
    }
}
