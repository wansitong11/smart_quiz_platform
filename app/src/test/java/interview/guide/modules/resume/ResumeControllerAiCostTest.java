package interview.guide.modules.resume;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiCostControl;
import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.resume.service.PaperAttemptService;
import interview.guide.modules.resume.service.PaperOverviewService;
import interview.guide.modules.resume.service.ResumeDeleteService;
import interview.guide.modules.resume.service.ResumeHistoryService;
import interview.guide.modules.resume.service.ResumeUploadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("试卷作答入口 AI 归因")
class ResumeControllerAiCostTest {

  @Mock
  private ResumeUploadService uploadService;
  @Mock
  private ResumeDeleteService deleteService;
  @Mock
  private ResumeHistoryService historyService;
  @Mock
  private PaperAttemptService attemptService;
  @Mock
  private PaperOverviewService overviewService;
  @Mock
  private AiCostControl aiCostControl;

  private ResumeController controller;

  @BeforeEach
  void setUp() {
    controller = new ResumeController(
        uploadService, deleteService, historyService, attemptService, overviewService, aiCostControl);
    AiCallContextHolder.set(new AiCallContext(
        7L, AiFeature.PAPER_EXTRACTION, "startAttempt",
        "request-1", "operation-1", "SYNC"));
  }

  @AfterEach
  void clearContext() {
    AiCallContextHolder.clear();
  }

  @Test
  @DisplayName("开始作答先预占，无模型调用时立即释放")
  void shouldReserveAndFinalizeStartAttempt() {
    InterviewSessionDTO session = new InterviewSessionDTO(
        "session-1", "", 0, 0, List.of(),
        InterviewSessionDTO.SessionStatus.CREATED,
        null, null, null, null);
    when(attemptService.startAttempt(9L)).thenReturn(session);

    controller.startAttempt(9L);

    verify(aiCostControl).reserveOrThrow(
        eq("operation-1"),
        eq(7L),
        eq(AiFeature.PAPER_EXTRACTION),
        any(AiCostEstimateInput.class));
    verify(aiCostControl).finalize("operation-1");
  }
}
