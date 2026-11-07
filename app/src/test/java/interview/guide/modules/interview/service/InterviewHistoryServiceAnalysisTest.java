package interview.guide.modules.interview.service;

import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("历史报告题目解析")
class InterviewHistoryServiceAnalysisTest {

  @Mock
  private InterviewPersistenceService interviewPersistenceService;
  @Mock
  private PdfExportService pdfExportService;
  @Mock
  private InterviewMapper interviewMapper;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("逐题报告返回题目解析")
  void shouldExposeQuestionAnalysisInAnswerDetail() {
    InterviewQuestionDTO question = question();
    PaperTree tree = PaperTree.fromFlatQuestions(List.of(question), "语文");
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setSessionId("session-analysis");
    session.setStatus(PracticeSessionEntity.SessionStatus.EVALUATED);
    session.setPaperJson(tree.toJson(objectMapper));
    when(interviewPersistenceService.findBySessionId("session-analysis"))
        .thenReturn(Optional.of(session));
    new InterviewHistoryService(
        interviewPersistenceService, pdfExportService, objectMapper, interviewMapper)
        .getInterviewDetail("session-analysis");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<InterviewDetailDTO.AnswerDetailDTO>> answersCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(interviewMapper).toDetailDTO(
        any(), any(), any(), any(), any(), answersCaptor.capture(), any(), any());

    assertThat(answersCaptor.getValue()).singleElement()
        .extracting(InterviewDetailDTO.AnswerDetailDTO::analysis)
        .isEqualTo("先定位原文关键句，再排除与文意相反的选项。");
  }

  @Test
  @DisplayName("未作答题回填参考答案、要点和人工复核状态")
  void shouldBackfillReferenceForUnansweredQuestion() {
    InterviewQuestionDTO question = unansweredQuestion();
    PaperTree tree = PaperTree.fromFlatQuestions(List.of(question), "语文");
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setSessionId("session-unanswered");
    session.setStatus(PracticeSessionEntity.SessionStatus.EVALUATED);
    session.setPaperJson(tree.toJson(objectMapper));
    when(interviewPersistenceService.findBySessionId("session-unanswered"))
        .thenReturn(Optional.of(session));

    new InterviewHistoryService(
        interviewPersistenceService, pdfExportService, objectMapper, interviewMapper)
        .getInterviewDetail("session-unanswered");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<InterviewDetailDTO.AnswerDetailDTO>> answersCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(interviewMapper).toDetailDTO(
        any(), any(), any(), any(), any(), answersCaptor.capture(), any(), any());

    InterviewDetailDTO.AnswerDetailDTO answer = answersCaptor.getValue().getFirst();
    assertThat(answer.referenceAnswer()).isEqualTo("A");
    assertThat(answer.keyPoints()).containsExactly("定位关键句");
    assertThat(answer.scoreStatus()).isEqualTo("MANUAL_REVIEW");
    assertThat(answer.lowConfidence()).isTrue();
    assertThat(answer.answerKeyConfidence()).isEqualTo(55);
    assertThat(answer.analysis()).isEqualTo("先定位原文关键句，再排除与文意相反的选项。");
  }

  @Test
  @DisplayName("旧批改数据里重复的解析从评语中移除")
  void shouldRemoveLegacyDuplicatedAnalysisFromFeedback() {
    InterviewQuestionDTO question = unansweredQuestion();
    String analysis = question.analysis();
    String feedback = "选择错误：你选了 B，正确答案 A。 解析：" + analysis + " 考点：定位关键句";
    PracticeAnswerEntity stored = new PracticeAnswerEntity();
    stored.setQuestionIndex(0);
    stored.setQuestion(question.question());
    stored.setCategory(question.category());
    stored.setUserAnswer("B");
    stored.setScore(0);
    stored.setFeedback(feedback);
    stored.setScoreStatus("AUTO");
    stored.setKeyPointsJson("[\"定位关键句\"]");

    PaperTree tree = PaperTree.fromFlatQuestions(List.of(question), "语文");
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setSessionId("session-legacy-feedback");
    session.setStatus(PracticeSessionEntity.SessionStatus.EVALUATED);
    session.setPaperJson(tree.toJson(objectMapper));
    session.setAnswers(List.of(stored));
    when(interviewPersistenceService.findBySessionId("session-legacy-feedback"))
        .thenReturn(Optional.of(session));
    when(interviewMapper.toAnswerDetailDTO(any(), anyList())).thenReturn(
        new InterviewDetailDTO.AnswerDetailDTO(
            0, question.question(), question.category(), "B", 0, feedback,
            "A", List.of("定位关键句"), null, "AUTO", false, 55, null, null));

    new InterviewHistoryService(
        interviewPersistenceService, pdfExportService, objectMapper, interviewMapper)
        .getInterviewDetail("session-legacy-feedback");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<InterviewDetailDTO.AnswerDetailDTO>> answersCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(interviewMapper).toDetailDTO(
        any(), any(), any(), any(), any(), answersCaptor.capture(), any(), any());

    InterviewDetailDTO.AnswerDetailDTO answer = answersCaptor.getValue().getFirst();
    assertThat(answer.feedback())
        .contains("选择错误：你选了 B，正确答案 A。")
        .contains("考点：定位关键句")
        .doesNotContain("解析：")
        .doesNotContain(analysis);
    assertThat(answer.analysis()).isEqualTo(analysis);
  }

  private InterviewQuestionDTO question() {
    return new InterviewQuestionDTO(
        0, "下列理解正确的是？", "chinese", "阅读理解", null,
        null, null, null, false, null, null, null, null, null, 5,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE, null,
        "先定位原文关键句，再排除与文意相反的选项。",
        1, 1, null, null, null, false);
  }

  private InterviewQuestionDTO unansweredQuestion() {
    return new InterviewQuestionDTO(
        0, "下列理解正确的是？", "chinese", "阅读理解", null,
        null, null, null, false, null, "A", List.of("定位关键句"), null, null, 5,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE, null,
        "先定位原文关键句，再排除与文意相反的选项。",
        1, 1, null, "TEXT", 55, false);
  }
}
