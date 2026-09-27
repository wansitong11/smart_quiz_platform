package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("对话记忆仓储（ChatMemoryRepository 适配）")
class RagChatMemoryRepositoryTest {

  @Mock
  private RagChatMessageRepository messageRepository;

  private KnowledgeBaseQueryProperties properties;
  private RagChatMemoryRepository repository;

  @BeforeEach
  void setUp() {
    properties = new KnowledgeBaseQueryProperties();
    repository = new RagChatMemoryRepository(messageRepository, properties);
  }

  private RagChatMessageEntity user(String content) {
    return message(content, true);
  }

  private RagChatMessageEntity assistant(String content) {
    return message(content, false);
  }

  private RagChatMessageEntity message(String content, boolean isUser) {
    RagChatMessageEntity entity = new RagChatMessageEntity();
    entity.setContent(content);
    entity.setType(isUser ? RagChatMessageEntity.MessageType.USER
        : RagChatMessageEntity.MessageType.ASSISTANT);
    entity.setCompleted(true);
    return entity;
  }

  @Test
  @DisplayName("只按窗口大小查询（LIMIT=窗口+1），再剔除当前提问、起点对齐 user 消息")
  void queriesOnlyWindowSizeAndTrims() {
    properties.getTools().setChatMemoryMaxMessages(4);
    // 会话：u1 a1 u2 a2 u3 a3 u4(当前提问)；仓储按倒序返回最近 5 条
    when(messageRepository.findRecentCompletedBySessionId(eq(1L), any(Pageable.class)))
        .thenReturn(List.of(user("u4"), assistant("a3"), user("u3"), assistant("a2"), user("u2")));

    List<Message> history = repository.findByConversationId("1");

    assertThat(history).extracting(Message::getText).containsExactly("u2", "a2", "u3", "a3");

    ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(messageRepository).findRecentCompletedBySessionId(eq(1L), pageCaptor.capture());
    assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(5);
  }

  @Test
  @DisplayName("窗口起点落在助手回答上时前移，避免只带半轮对话")
  void snapsWindowStartToUserMessage() {
    properties.getTools().setChatMemoryMaxMessages(3);
    when(messageRepository.findRecentCompletedBySessionId(eq(2L), any(Pageable.class)))
        .thenReturn(List.of(user("u4"), assistant("a3"), user("u3"), assistant("a2")));

    List<Message> history = repository.findByConversationId("2");

    assertThat(history).extracting(Message::getText).containsExactly("u3", "a3");
  }

  @Test
  @DisplayName("短会话不做裁剪，也不丢开头的消息")
  void keepsShortConversationIntact() {
    properties.getTools().setChatMemoryMaxMessages(10);
    when(messageRepository.findRecentCompletedBySessionId(eq(4L), any(Pageable.class)))
        .thenReturn(List.of(user("u2"), assistant("a1"), user("u1")));

    List<Message> history = repository.findByConversationId("4");

    assertThat(history).extracting(Message::getText).containsExactly("u1", "a1");
  }

  @Test
  @DisplayName("会话没有消息或 conversationId 非法时返回空列表")
  void returnsEmptyForUnknownSession() {
    when(messageRepository.findRecentCompletedBySessionId(eq(3L), any(Pageable.class)))
        .thenReturn(List.of());
    assertThat(repository.findByConversationId("3")).isEmpty();
    assertThat(repository.findByConversationId("not-a-number")).isEmpty();
    assertThat(repository.findByConversationId(null)).isEmpty();
  }

  @Test
  @DisplayName("只读适配器：保存与删除都不落库（消息由业务侧负责写入）")
  void writeOperationsAreNoOps() {
    repository.saveAll("1", List.of(new UserMessage("x")));
    repository.deleteByConversationId("1");
    assertThat(repository.findConversationIds()).isEmpty();
  }
}
