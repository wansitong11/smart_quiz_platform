package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KbOcrTextDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库图片识图服务测试")
class KnowledgeBaseImageOcrServiceTest {

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;
  @Mock
  private LlmProviderRegistry providerRegistry;

  private KnowledgeBaseImageOcrService service;
  private ChatClient chatClient;

  @BeforeEach
  void setUp() throws Exception {
    chatClient = mock(ChatClient.class);
    when(providerRegistry.getDefaultVisionChatClient()).thenReturn(chatClient);
    service = new KnowledgeBaseImageOcrService(
        structuredOutputInvoker, providerRegistry, new DefaultResourceLoader());
  }

  @Test
  @DisplayName("识图成功时返回去掉首尾空白的文字")
  void shouldReturnTrimmedText() {
    when(structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
        eq(chatClient), anyString(), anyString(), anyList(), any(),
        eq(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED), anyString(), anyString(), any()))
        .thenReturn(new KbOcrTextDTO("  识别文字  "));

    String text = service.ocrPageImage(new byte[] {1, 2, 3}, 1, 1);

    assertThat(text).isEqualTo("识别文字");
  }

  @Test
  @DisplayName("识图返回空文字时抛出知识库解析异常")
  void shouldThrowWhenOcrTextBlank() {
    when(structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
        eq(chatClient), anyString(), anyString(), anyList(), any(),
        eq(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED), anyString(), anyString(), any()))
        .thenReturn(new KbOcrTextDTO("   "));

    assertThatThrownBy(() -> service.ocrDocxImage(new byte[] {1}, 1))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("没有返回文字");
  }
}
