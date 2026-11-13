package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.LlmProviderRegistry.ShortTextOptions;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.service.RagEvidenceReviewService.ReviewDecision;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

class RagEvidenceReviewServiceTest {
  private final LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
  private final StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
  private final KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
  private final ChatClient client = mock(ChatClient.class);
  private final RagContextAssembler assembler = new RagContextAssembler(mock(VectorRepository.class),
      mock(KnowledgeBaseListService.class), properties, mock(RagMetrics.class));

  private RagEvidenceReviewService service() throws Exception {
    return new RagEvidenceReviewService(registry, invoker, properties, assembler,
        mock(RagMetrics.class), new ObjectMapper(), new DefaultResourceLoader());
  }

  @Test
  @DisplayName("严格校验整批保留编号，允许空，不接受缺项重复未知或块编号")
  void validatesDecision() {
    assertThat(RagEvidenceReviewService.validDecision(new ReviewDecision(List.of()), Set.of("E1"))).isTrue();
    assertThat(RagEvidenceReviewService.validDecision(new ReviewDecision(List.of("E2", "E1")), Set.of("E1", "E2"))).isTrue();
    assertThat(RagEvidenceReviewService.validDecision(null, Set.of("E1"))).isFalse();
    assertThat(RagEvidenceReviewService.validDecision(new ReviewDecision(null), Set.of("E1"))).isFalse();
    for (List<String> ids : List.of(List.of("E1", "E1"), List.of("C1"), List.of("E9"), List.of(""))) {
      assertThat(RagEvidenceReviewService.validDecision(new ReviewDecision(ids), Set.of("E1", "E2"))).isFalse();
    }
  }

  @Test
  @DisplayName("一次短文本调用恢复计费上下文，完整原文与问题送入复核，缓存不重复调用")
  void oneCallAndCache() throws Exception {
    var session = RagEvidenceSessionTest.session();
    when(registry.getChatClientOrDefault(eq(""), any(ShortTextOptions.class))).thenReturn(client);
    when(invoker.invokeOnceWithoutSchemaValidation(eq(client), anyString(), anyString(), any(),
        any(), anyString(), anyString(), any(), any())).thenAnswer(call -> {
          assertThat(AiCallContextHolder.get()).isEqualTo(session.callContext());
          assertThat(call.<String>getArgument(2)).contains("当前问题", "完整原文", "C1", "E1");
          return new ReviewDecision(List.of("E1"));
        });
    var service = service();
    var prepared = RagEvidenceSessionTest.prepared("id", 23, "完整原文");
    assertThat(service.review("当前问题", prepared, session).text()).contains("完整原文", "B1C1", "E1");
    assertThat(service.review("当前问题", prepared, session).text()).doesNotContain("完整原文");
    verify(invoker).invokeOnceWithoutSchemaValidation(eq(client), anyString(), anyString(), any(),
        any(), anyString(), anyString(), any(), any());
    verify(registry, org.mockito.Mockito.atLeastOnce()).getChatClientOrDefault("",
        new ShortTextOptions(Duration.ofSeconds(15), 256));
    assertThat(AiCallContextHolder.get()).isNull();
  }

  @Test
  @DisplayName("输入超限零次模型调用且本轮失败关闭")
  void oversizedInput() throws Exception {
    properties.getReview().setMaxInputTokens(2048);
    when(registry.getChatClientOrDefault(eq(""), any(ShortTextOptions.class))).thenReturn(client);
    var session = RagEvidenceSessionTest.session();
    var prepared = RagEvidenceSessionTest.prepared("id", 4, "不得截断".repeat(3000));
    assertThatThrownBy(() -> service().review("当前问题", prepared, session))
        .isInstanceOf(BusinessException.class).hasMessageContaining("INPUT_LIMIT");
    verifyNoInteractions(invoker);
    assertThat(session.failed()).isTrue();
  }

  @Test
  @DisplayName("上游超时不恢复原始证据，不允许继续生成")
  void timeoutFailsClosed() throws Exception {
    when(registry.getChatClientOrDefault(eq(""), any(ShortTextOptions.class))).thenReturn(client);
    when(invoker.invokeOnceWithoutSchemaValidation(eq(client), anyString(), anyString(), any(),
        any(), anyString(), anyString(), any(), any())).thenThrow(RagEvidenceSession.failure("timeout"));
    var session = RagEvidenceSessionTest.session();
    assertThatThrownBy(() -> service().review("当前问题",
        RagEvidenceSessionTest.prepared("id", 4, "原文"), session)).isInstanceOf(BusinessException.class);
    assertThat(session.failed()).isTrue();
    assertThat(session.acceptedUnits()).isEmpty();
  }
}
