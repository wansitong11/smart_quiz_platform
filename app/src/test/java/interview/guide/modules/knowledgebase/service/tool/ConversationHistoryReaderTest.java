package interview.guide.modules.knowledgebase.service.tool;

import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("会话记录读取（工具侧）")
class ConversationHistoryReaderTest {

  @Mock
  private RagChatMessageRepository messageRepository;

  private final ConversationHistoryReader reader = new ConversationHistoryReader(null);

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
  @DisplayName("order=ASC：取最早的 N 条（用于回答「第一个问题」）")
  void readsEarliestMessages() {
    ConversationHistoryReader service = new ConversationHistoryReader(messageRepository);
    when(messageRepository.findFirstCompletedBySessionId(eq(1L), any(Pageable.class)))
        .thenReturn(List.of(user("u1"), assistant("a1"), user("u2")));

    List<RagChatMessageEntity> messages = service.read(1L, 2, true, true);

    assertThat(messages).extracting(RagChatMessageEntity::getContent).containsExactly("u1", "a1");
    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(messageRepository).findFirstCompletedBySessionId(eq(1L), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(3);   // limit + 1
  }

  @Test
  @DisplayName("order=DESC：取最近的 N 条，并剔除当前提问")
  void readsLatestMessagesAndDropsCurrentQuestion() {
    ConversationHistoryReader service = new ConversationHistoryReader(messageRepository);
    // 倒序返回：u3(当前提问) a2 u2
    when(messageRepository.findRecentCompletedBySessionId(eq(2L), any(Pageable.class)))
        .thenReturn(List.of(user("u3"), assistant("a2"), user("u2")));

    List<RagChatMessageEntity> messages = service.read(2L, 5, false, true);

    assertThat(messages).extracting(RagChatMessageEntity::getContent).containsExactly("u2", "a2");
  }

  @Test
  @DisplayName("取到的条数等于 limit+1 说明没到会话末尾，不误删最后一条")
  void doesNotDropLastMessageWhenSliceIsFull() {
    ConversationHistoryReader service = new ConversationHistoryReader(messageRepository);
    when(messageRepository.findFirstCompletedBySessionId(eq(3L), any(Pageable.class)))
        .thenReturn(List.of(user("u1"), assistant("a1"), user("u2")));

    List<RagChatMessageEntity> messages = service.read(3L, 2, true, true);

    assertThat(messages).extracting(RagChatMessageEntity::getContent).containsExactly("u1", "a1");
  }

  @Test
  @DisplayName("渲染带序号与角色，长消息截断")
  void rendersWithIndexAndTruncates() {
    String longContent = "x".repeat(700);
    String rendered = reader.render(List.of(user("u1"), assistant(longContent)));

    assertThat(rendered).startsWith("[1] 用户：u1").contains("[2] 助手：").contains("（已截断）");
  }
}
