package interview.guide.modules.interview;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiCost;
import interview.guide.common.ai.AiCostControl;
import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.modules.interview.model.BatchSubmitAnswerResponse;
import interview.guide.modules.interview.model.BatchSubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.resume.service.PaperAttemptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试交卷接口 AI 归因")
class InterviewControllerAiCostTest {

  @Mock
  private InterviewSessionService sessionService;
  @Mock
  private InterviewHistoryService historyService;
  @Mock
  private InterviewPersistenceService persistenceService;
  @Mock
  private PaperAttemptService paperAttemptService;
  @Mock
  private AiCostControl aiCostControl;

  private InterviewController controller;

  @BeforeEach
  void setUp() {
    controller = new InterviewController(
        sessionService, historyService, persistenceService, paperAttemptService, aiCostControl);
    AiCallContextHolder.set(new AiCallContext(
        7L, AiFeature.ANSWER_EVALUATE, "submit", "request-1", "operation-1", "SYNC"));
  }

  @AfterEach
  void clearContext() {
    AiCallContextHolder.clear();
  }

  @Test
  @DisplayName("逐题提交、整卷提交与提前交卷都携带批改上下文")
  void shouldAnnotateEvaluationEntrypoints() throws Exception {
    assertEvaluationAnnotation(InterviewController.class.getMethod(
        "submitAnswer", String.class, Map.class), "submit");
    assertEvaluationAnnotation(InterviewController.class.getMethod(
        "submitWholePaper", String.class, BatchSubmitAnswerRequest.class), "submit");
    assertEvaluationAnnotation(InterviewController.class.getMethod(
        "completeInterview", String.class), "complete");
  }

  @Test
  @DisplayName("逐题提交到最后一题时不提前结算，交给异步批改结算")
  void shouldKeepReservationForAsyncEvaluation() {
    when(sessionService.submitAnswer(any())).thenReturn(
        new SubmitAnswerResponse(false, null, 3, 3));

    controller.submitAnswer("session-1", Map.of("questionIndex", 2, "answer", "A"));

    verify(aiCostControl).reserveOrThrow(
        eq("operation-1"), eq(7L), eq(AiFeature.ANSWER_EVALUATE), any(AiCostEstimateInput.class));
    verify(aiCostControl, never()).finalize(any());
  }

  @Test
  @DisplayName("逐题提交未结束时立即释放预占")
  void shouldFinalizeWhenNoEvaluationWasQueued() {
    when(sessionService.submitAnswer(any())).thenReturn(
        new SubmitAnswerResponse(true, null, 1, 3));

    controller.submitAnswer("session-1", Map.of("questionIndex", 0, "answer", "A"));

    verify(aiCostControl).finalize("operation-1");
  }

  @Test
  @DisplayName("整卷交卷按作答题数预占，成功后交给异步批改结算")
  void shouldReserveWholePaperByAnswerCount() {
    when(sessionService.submitWholePaper(eq("session-1"), any())).thenReturn(
        new BatchSubmitAnswerResponse("session-1", 2, 3, 0, "COMPLETED", "PENDING"));
    BatchSubmitAnswerRequest request = new BatchSubmitAnswerRequest(List.of(
        new BatchSubmitAnswerRequest.AnswerItem(0, null, null, "A"),
        new BatchSubmitAnswerRequest.AnswerItem(1, null, null, "B")));

    controller.submitWholePaper("session-1", request);

    ArgumentCaptor<AiCostEstimateInput> estimate =
        ArgumentCaptor.forClass(AiCostEstimateInput.class);
    verify(aiCostControl).reserveOrThrow(
        eq("operation-1"), eq(7L), eq(AiFeature.ANSWER_EVALUATE), estimate.capture());
    assertThat(estimate.getValue().questionCount()).isEqualTo(2L);
    verify(aiCostControl, never()).finalize(any());
  }

  @Test
  @DisplayName("再练一遍入口先预占，无模型调用时立即释放")
  void shouldReserveAndFinalizeStartAttempt() {
    InterviewSessionDTO session = new InterviewSessionDTO(
        "session-1", "", 0, 0, List.of(),
        InterviewSessionDTO.SessionStatus.CREATED,
        null, null, null, null);
    when(paperAttemptService.startAttemptFromSession("session-1")).thenReturn(session);

    controller.startAttempt("session-1");

    verify(aiCostControl).reserveOrThrow(
        eq("operation-1"),
        eq(7L),
        eq(AiFeature.PAPER_EXTRACTION),
        any(AiCostEstimateInput.class));
    verify(aiCostControl).finalize("operation-1");
  }

  private void assertEvaluationAnnotation(Method method, String operation) {
    AiCost annotation = method.getAnnotation(AiCost.class);
    assertThat(annotation).as(method.getName()).isNotNull();
    assertThat(annotation.feature()).isEqualTo(AiFeature.ANSWER_EVALUATE);
    assertThat(annotation.operation()).isEqualTo(operation);
  }
}
