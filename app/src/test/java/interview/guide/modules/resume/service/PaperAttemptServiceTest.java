package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.resume.model.PracticePaperEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("试卷作答")
class PaperAttemptServiceTest {

  private static final Long PAPER_ID = 7L;

  @Mock
  private ResumePersistenceService resumePersistenceService;
  @Mock
  private InterviewPersistenceService interviewPersistenceService;
  @Mock
  private InterviewSessionService interviewSessionService;
  @Mock
  private RedisService redisService;

  private ObjectMapper objectMapper;
  private PaperAttemptService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    service = new PaperAttemptService(resumePersistenceService, interviewPersistenceService,
        interviewSessionService, redisService, objectMapper);
    when(redisService.executeWithLock(anyString(), anyLong(), anyLong(), any(), any()))
        .thenAnswer(invocation -> {
          RedisService.LockedOperation<?> operation = invocation.getArgument(4);
          return operation.execute();
        });
  }

  private InterviewQuestionDTO answeredQuestion(int index) {
    return new InterviewQuestionDTO(
        index, "第 " + (index + 1) + " 题", "SINGLE_CHOICE", "现代文阅读", null,
        "A", 3, "答对", false, null, "A", List.of("要点一"), "选对得满分", null, 3,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE, null, "甲项与材料一致。"
    );
  }

  private PaperTree treeWithAnsweredQuestions() {
    PaperNode section = PaperNode.section("n1", 1, "一、现代文阅读", null, List.of(
        PaperNode.material("m1", 1, "材料一", "中华文明源远流长……"),
        PaperNode.ofQuestion(2, answeredQuestion(0)),
        PaperNode.ofQuestion(3, answeredQuestion(1))
    ));
    return PaperTree.recalculate(new PaperTree(PaperTree.CURRENT_VERSION,
        PaperNode.paper("n0", "语文模拟卷", null, List.of(section))));
  }

  private PracticeSessionEntity session(PracticeSessionEntity.SessionStatus status) {
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setSessionId("existing-session");
    session.setSourceType("PAPER");
    session.setStatus(status);
    session.setSkillId("chinese");
    session.setDifficulty("mid");
    session.setLlmProvider("glm");
    session.setSubjectCategory("chinese");
    session.setPaperJson(treeWithAnsweredQuestions().toJson(objectMapper));
    return session;
  }

  @Test
  @DisplayName("上一次没交卷时继续那一次，不新建会话")
  void resumesUnfinishedAttempt() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(new PracticePaperEntity()));
    when(interviewPersistenceService.findByResumeId(PAPER_ID))
        .thenReturn(List.of(session(PracticeSessionEntity.SessionStatus.IN_PROGRESS)));
    InterviewSessionDTO existing = new InterviewSessionDTO("existing-session", "", 2, 0, List.of(),
        InterviewSessionDTO.SessionStatus.IN_PROGRESS, null, null, null);
    when(interviewSessionService.getSession("existing-session")).thenReturn(existing);

    InterviewSessionDTO result = service.startAttempt(PAPER_ID);

    assertThat(result.sessionId()).isEqualTo("existing-session");
    verify(interviewSessionService, never()).createSessionFromQuestions(
        any(), any(), any(), any(), any(), any(), any(), any(), anyString());
  }

  @Test
  @DisplayName("上一次已交卷时基于同一份切题结果新建作答，且清空上次的答案")
  void startsNewAttemptAfterSubmitted() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(new PracticePaperEntity()));
    when(interviewPersistenceService.findByResumeId(PAPER_ID))
        .thenReturn(List.of(session(PracticeSessionEntity.SessionStatus.EVALUATED)));
    InterviewSessionDTO created = new InterviewSessionDTO("new-session", "", 2, 0, List.of(),
        InterviewSessionDTO.SessionStatus.CREATED, null, null, null);
    when(interviewSessionService.createSessionFromQuestions(
        any(), any(), any(), any(), any(), any(), any(), eq(PAPER_ID), eq("PAPER")))
        .thenReturn(created);

    InterviewSessionDTO result = service.startAttempt(PAPER_ID);

    assertThat(result.sessionId()).isEqualTo("new-session");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), any(), eq("glm"), eq("chinese"), eq("mid"), any(), eq("chinese"),
        eq(PAPER_ID), eq("PAPER"));
    assertThat(captor.getValue()).hasSize(2)
        .allSatisfy(question -> {
          assertThat(question.userAnswer()).isNull();
          assertThat(question.score()).isNull();
          assertThat(question.feedback()).isNull();
          assertThat(question.referenceAnswer()).isEqualTo("A");
        });
  }

  @Test
  @DisplayName("材料还没有切题结果时拒绝开始作答")
  void rejectsPaperWithoutExtraction() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(new PracticePaperEntity()));
    when(interviewPersistenceService.findByResumeId(PAPER_ID)).thenReturn(List.of());

    assertThatThrownBy(() -> service.startAttempt(PAPER_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("还没有切题结果");
  }

  @Test
  @DisplayName("材料不存在时报错")
  void rejectsMissingPaper() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.startAttempt(PAPER_ID))
        .isInstanceOf(BusinessException.class);
  }
}
