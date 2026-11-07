package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.resume.model.PaperOverviewDTO;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.RecognitionStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("试卷概览")
class PaperOverviewServiceTest {

  private static final Long PAPER_ID = 9L;

  @Mock
  private ResumePersistenceService resumePersistenceService;
  @Mock
  private PaperStructureService structureService;

  private PaperOverviewService service() {
    return new PaperOverviewService(resumePersistenceService, structureService);
  }

  @Test
  @DisplayName("已切题试卷返回只读结构并剥离答案")
  void shouldReturnReadOnlyTreeWithoutAnswerKeys() {
    PracticePaperEntity paper = paper(RecognitionStatus.READY, AsyncTaskStatus.COMPLETED);
    InterviewQuestionDTO question = answeredQuestion();
    PaperTree tree = PaperTree.fromFlatQuestions(List.of(question), "数学");
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(paper));
    when(structureService.buildCached(PAPER_ID)).thenReturn(
        new PaperStructureService.BuiltStructure("math", tree, List.of(question), 5, List.of()));

    PaperOverviewDTO overview = service().overview(PAPER_ID);

    verify(structureService).buildCached(PAPER_ID);
    verify(structureService, never()).build(PAPER_ID);
    assertThat(overview.paperId()).isEqualTo(PAPER_ID);
    assertThat(overview.subjectId()).isEqualTo("math");
    assertThat(overview.totalScore()).isEqualTo(5);
    assertThat(overview.questionCount()).isEqualTo(1);
    assertThat(overview.paperTree().flattenQuestions()).singleElement().satisfies(item -> {
      assertThat(item.question()).isEqualTo("下列结论正确的是？");
      assertThat(item.topicSummary()).isNull();
      assertThat(item.userAnswer()).isNull();
      assertThat(item.score()).isNull();
      assertThat(item.feedback()).isNull();
      assertThat(item.referenceAnswer()).isNull();
      assertThat(item.keyPoints()).isEmpty();
      assertThat(item.scoringRubric()).isNull();
      assertThat(item.analysis()).isNull();
      assertThat(item.visualContext()).isNull();
      assertThat(item.options()).extracting(QuestionOption::key).containsExactly("A", "B");
    });
  }

  @Test
  @DisplayName("识别未完成时拒绝预览")
  void shouldRejectWhenRecognitionIsNotReady() {
    PracticePaperEntity paper = paper(RecognitionStatus.NEEDS_REVIEW, AsyncTaskStatus.COMPLETED);
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(paper));

    assertThatThrownBy(() -> service().overview(PAPER_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("识别或等待校对");
    verifyNoInteractions(structureService);
  }

  @Test
  @DisplayName("切题未完成时拒绝预览")
  void shouldRejectWhenExtractionIsNotCompleted() {
    PracticePaperEntity paper = paper(RecognitionStatus.READY, AsyncTaskStatus.PROCESSING);
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(paper));

    assertThatThrownBy(() -> service().overview(PAPER_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("切题完成后");
    verifyNoInteractions(structureService);
  }

  @Test
  @DisplayName("试卷不存在或无权访问时返回未找到")
  void shouldRejectMissingPaper() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().overview(PAPER_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("试卷不存在");
    verifyNoInteractions(structureService);
  }

  private PracticePaperEntity paper(RecognitionStatus recognition, AsyncTaskStatus analysis) {
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setRecognitionStatus(recognition);
    paper.setAnalysisStatus(analysis);
    return paper;
  }

  private InterviewQuestionDTO answeredQuestion() {
    return new InterviewQuestionDTO(
        0, "下列结论正确的是？", "SINGLE_CHOICE", "数学", "补集运算",
        "A", 4, "答案正确", false, null,
        "A", List.of("集合的补集运算"), "选对得分", "材料上下文", 5,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(new QuestionOption("A", "甲"), new QuestionOption("B", "乙")),
        "由补集定义可得 A。", 1, 1, "图形摘要", "TEXT", 90, false);
  }
}
