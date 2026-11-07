package interview.guide.modules.resume.service;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiCostControl;
import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.resume.model.PracticePaperEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("试卷作答")
class PaperAttemptServiceTest {

  private static final Long PAPER_ID = 7L;
  private static final long CURRENT_CONTENT_REVISION = 1_000L;

  @Mock
  private ResumePersistenceService resumePersistenceService;
  @Mock
  private InterviewPersistenceService interviewPersistenceService;
  @Mock
  private InterviewSessionService interviewSessionService;
  @Mock
  private PaperStructureService structureService;
  @Mock
  private ResumeUploadService resumeUploadService;
  @Mock
  private RedisService redisService;
  @Mock
  private PaperContentGuard paperContentGuard;
  @Mock
  private AiCostControl aiCostControl;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicBoolean contentLockHeld = new AtomicBoolean();
  private PaperAttemptService service;

  @BeforeEach
  void setUp() {
    service = new PaperAttemptService(resumePersistenceService, interviewPersistenceService,
        interviewSessionService, structureService, resumeUploadService, redisService,
        paperContentGuard, aiCostControl);
    when(redisService.executeWithLock(anyString(), anyLong(), any(), any()))
        .thenAnswer(invocation -> {
          RedisService.LockedOperation<?> operation = invocation.getArgument(3);
          return operation.execute();
        });
    when(paperContentGuard.withPaperLock(anyLong(), any(Supplier.class)))
        .thenAnswer(invocation -> {
          contentLockHeld.set(true);
          try {
            Supplier<?> operation = invocation.getArgument(1);
            return operation.get();
          } finally {
            contentLockHeld.set(false);
          }
        });
  }

  @AfterEach
  void clearAiContext() {
    AiCallContextHolder.clear();
  }

  private InterviewQuestionDTO answeredQuestion(int index) {
    return new InterviewQuestionDTO(
        index, "第 " + (index + 1) + " 题", "SINGLE_CHOICE", "现代文阅读", null,
        "A", 3, "答对", false, null, "A", List.of("要点一"), "选对得满分", null, 3,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE, null, "甲项与材料一致。", null, null,
        null, null, null, false
    ).withAnswerKey(new AnswerKeyDTO(
        index, "A", Map.of("A", true), "看图判断", List.of("要点一"),
        "选对得满分", null, false, 90, AnswerKeyDTO.SOURCE_VISION));
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
    session.setPaperContentRevision(CURRENT_CONTENT_REVISION);
    session.setPaperJson(treeWithAnsweredQuestions().toJson(objectMapper));
    return session;
  }

  private PracticePaperEntity readyPaper() {
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setRecognitionStatus(interview.guide.modules.resume.model.RecognitionStatus.READY);
    paper.setAnalysisStatus(interview.guide.common.model.AsyncTaskStatus.COMPLETED);
    paper.setContentRevision(CURRENT_CONTENT_REVISION);
    return paper;
  }

  @Test
  @DisplayName("上一次没交卷时继续那一次，不新建会话")
  void resumesUnfinishedAttempt() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(readyPaper()));
    when(interviewPersistenceService.findByResumeId(PAPER_ID))
        .thenReturn(List.of(session(PracticeSessionEntity.SessionStatus.IN_PROGRESS)));
    InterviewSessionDTO existing = new InterviewSessionDTO("existing-session", "", 2, 0, List.of(),
        InterviewSessionDTO.SessionStatus.IN_PROGRESS, null, null, null, null);
    when(interviewSessionService.getSession("existing-session")).thenReturn(existing);

    InterviewSessionDTO result = service.startAttempt(PAPER_ID);

    assertThat(result.sessionId()).isEqualTo("existing-session");
    verify(interviewSessionService).markPaperAttemptStarted("existing-session");
    verify(interviewSessionService, never()).createSessionFromQuestions(
        any(), any(), any(), any(), any(), any(), any(), any(), anyString());
  }

  @Test
  @DisplayName("上一次已交卷时基于同一份切题结果新建作答，且清空上次的答案")
  void startsNewAttemptAfterSubmitted() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(readyPaper()));
    when(interviewPersistenceService.findByResumeId(PAPER_ID))
        .thenReturn(List.of(session(PracticeSessionEntity.SessionStatus.EVALUATED)));
    PaperTree tree = treeWithAnsweredQuestions();
    when(structureService.build(PAPER_ID)).thenReturn(built(tree));
    InterviewSessionDTO created = new InterviewSessionDTO("new-session", "", 2, 0, List.of(),
        InterviewSessionDTO.SessionStatus.CREATED, null, null, null, null);
    when(interviewSessionService.createSessionFromQuestions(
        any(), any(), any(), any(), any(), any(), any(), eq(PAPER_ID), eq("PAPER")))
        .thenReturn(created);
    when(interviewSessionService.getSession("new-session")).thenReturn(created);

    InterviewSessionDTO result = service.startAttempt(PAPER_ID);

    assertThat(result.sessionId()).isEqualTo("new-session");
    verify(interviewSessionService).markPaperAttemptStarted("new-session");

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
  @DisplayName("答案键不可用时自动重新分析，不创建无效作答")
  void reanalyzesWhenAnswerKeysAreUnusable() {
    AiCallContextHolder.set(new AiCallContext(
        7L, AiFeature.PAPER_EXTRACTION, "startAttempt",
        "request-1", "sync-operation", "SYNC"));
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(readyPaper()));
    when(interviewPersistenceService.findByResumeId(PAPER_ID))
        .thenReturn(List.of(session(PracticeSessionEntity.SessionStatus.EVALUATED)));
    PaperTree staleTree = treeWithAnsweredQuestions().withQuestions(
        treeWithAnsweredQuestions().flattenQuestions().stream()
            .map(InterviewQuestionDTO::forAttemptView)
            .toList());
    when(structureService.build(PAPER_ID)).thenReturn(built(staleTree));
    AtomicReference<AiCallContext> asyncContext = new AtomicReference<>();
    doAnswer(invocation -> {
      asyncContext.set(AiCallContextHolder.get());
      return null;
    }).when(resumeUploadService).reanalyze(PAPER_ID);

    assertThatThrownBy(() -> service.startAttempt(PAPER_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已自动重新分析");

    ArgumentCaptor<String> operationId = ArgumentCaptor.forClass(String.class);
    verify(aiCostControl).reserveOrThrow(
        operationId.capture(),
        eq(7L),
        eq(AiFeature.PAPER_EXTRACTION),
        any(AiCostEstimateInput.class));
    verify(resumeUploadService).reanalyze(PAPER_ID);
    assertThat(asyncContext.get()).isNotNull();
    assertThat(asyncContext.get().operationId())
        .isEqualTo(operationId.getValue())
        .isNotEqualTo("sync-operation");
    assertThat(asyncContext.get().operation()).isEqualTo("startAttempt.reanalyze");
    verify(aiCostControl, never()).finalize(anyString());
    verify(interviewSessionService, never()).createSessionFromQuestions(
        any(), any(), any(), any(), any(), any(), any(), any(), anyString());
  }

  @Test
  @DisplayName("答案已存在但置信度低时仍允许作答并保留低置信度提示")
  void allowsAttemptWithLowConfidenceAnswer() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(readyPaper()));
    when(interviewPersistenceService.findByResumeId(PAPER_ID))
        .thenReturn(List.of(session(PracticeSessionEntity.SessionStatus.EVALUATED)));
    PaperTree lowConfidenceTree = treeWithAnsweredQuestions().withQuestions(
        treeWithAnsweredQuestions().flattenQuestions().stream()
            .map(question -> question.withAnswerKey(new AnswerKeyDTO(
                question.questionIndex(), "A", Map.of("A", true), "低置信度候选答案",
                List.of("要点一"), "选对得满分", null, false, 20,
                AnswerKeyDTO.SOURCE_VISION)))
            .toList());
    when(structureService.build(PAPER_ID)).thenReturn(built(lowConfidenceTree));
    InterviewSessionDTO created = new InterviewSessionDTO("new-session", "", 2, 0, List.of(),
        InterviewSessionDTO.SessionStatus.CREATED, null, null, null, null);
    when(interviewSessionService.createSessionFromQuestions(
        any(), any(), any(), any(), any(), any(), any(), eq(PAPER_ID), eq("PAPER")))
        .thenReturn(created);
    when(interviewSessionService.getSession("new-session")).thenReturn(created);

    InterviewSessionDTO result = service.startAttempt(PAPER_ID);

    assertThat(result.sessionId()).isEqualTo("new-session");
    verify(resumeUploadService, never()).reanalyze(anyLong());
  }

  @Test
  @DisplayName("材料还没有切题结果时拒绝开始作答")
  void rejectsPaperWithoutExtraction() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(readyPaper()));
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

  @Test
  @DisplayName("未完成会话的内容版本不一致时拒绝继续")
  void rejectsUnfinishedAttemptWithStaleContentRevision() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(readyPaper()));
    PracticeSessionEntity stale = session(PracticeSessionEntity.SessionStatus.IN_PROGRESS);
    stale.setPaperContentRevision(9L);
    when(interviewPersistenceService.findByResumeId(PAPER_ID)).thenReturn(List.of(stale));

    assertThatThrownBy(() -> service.startAttempt(PAPER_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("试卷内容已更新");

    verify(interviewSessionService, never()).markPaperAttemptStarted("existing-session");
    verify(interviewSessionService, never()).getSession("existing-session");
  }

  @Test
  @DisplayName("过期的未开始草稿被忽略，按当前结构新建会话")
  void ignoresStaleCreatedDraftAndStartsFromCurrentStructure() {
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(readyPaper()));
    PracticeSessionEntity staleDraft = session(PracticeSessionEntity.SessionStatus.CREATED);
    staleDraft.setPaperContentRevision(9L);
    when(interviewPersistenceService.findByResumeId(PAPER_ID))
        .thenReturn(List.of(staleDraft));
    PaperTree currentTree = treeWithAnsweredQuestions();
    when(structureService.build(PAPER_ID)).thenReturn(built(currentTree));
    InterviewSessionDTO created = new InterviewSessionDTO("new-session", "", 2, 0, List.of(),
        InterviewSessionDTO.SessionStatus.CREATED, null, null, null, null);
    when(interviewSessionService.createSessionFromQuestions(
        any(), any(), any(), any(), any(), any(), any(), eq(PAPER_ID), eq("PAPER")))
        .thenReturn(created);
    when(interviewSessionService.getSession("new-session")).thenReturn(created);

    InterviewSessionDTO result = service.startAttempt(PAPER_ID);

    assertThat(result.sessionId()).isEqualTo("new-session");
    verify(structureService).build(PAPER_ID);
    verify(interviewSessionService).markPaperAttemptStarted("new-session");
    verify(interviewSessionService, never()).markPaperAttemptStarted("existing-session");
  }

  @Test
  @DisplayName("识别或切题未完成时拒绝开始作答")
  void rejectsPaperStillBeingProcessed() {
    PracticePaperEntity paper = readyPaper();
    paper.setRecognitionStatus(interview.guide.modules.resume.model.RecognitionStatus.PROCESSING);
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(paper));

    assertThatThrownBy(() -> service.startAttempt(PAPER_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("仍在识别");
  }

  @Test
  @DisplayName("结构构建在内容锁外执行")
  void buildsStructureOutsideContentLock() {
    PracticePaperEntity paper = readyPaper();
    when(resumePersistenceService.findById(PAPER_ID)).thenReturn(Optional.of(paper));
    when(interviewPersistenceService.findByResumeId(PAPER_ID))
        .thenReturn(List.of(session(PracticeSessionEntity.SessionStatus.EVALUATED)));
    PaperTree tree = treeWithAnsweredQuestions();
    InterviewSessionDTO created = new InterviewSessionDTO("new-session", "", 2, 0, List.of(),
        InterviewSessionDTO.SessionStatus.CREATED, null, null, null, null);
    when(interviewSessionService.createSessionFromQuestions(
        any(), any(), any(), any(), any(), any(), any(), eq(PAPER_ID), eq("PAPER")))
        .thenReturn(created);
    when(interviewSessionService.getSession("new-session")).thenReturn(created);

    when(structureService.build(PAPER_ID)).thenAnswer(invocation -> {
      assertThat(contentLockHeld).isFalse();
      return built(tree);
    });

    service.startAttempt(PAPER_ID);

    verify(structureService).build(PAPER_ID);
  }

  @Test
  @DisplayName("构建期间内容代次变化时拒绝创建旧树会话")
  void rejectsAttemptWhenRevisionChangesDuringBuild() {
    PracticePaperEntity original = readyPaper();
    PracticePaperEntity changed = readyPaper();
    changed.setContentRevision(CURRENT_CONTENT_REVISION + 1);
    when(resumePersistenceService.findById(PAPER_ID))
        .thenReturn(Optional.of(original), Optional.of(changed));
    when(interviewPersistenceService.findByResumeId(PAPER_ID))
        .thenReturn(List.of(session(PracticeSessionEntity.SessionStatus.EVALUATED)));
    when(structureService.build(PAPER_ID)).thenReturn(built(treeWithAnsweredQuestions()));

    assertThatThrownBy(() -> service.startAttempt(PAPER_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("内容在开始作答前已更新");

    verify(interviewSessionService, never()).createSessionFromQuestions(
        any(), any(), any(), any(), any(), any(), any(), any(), anyString());
  }

  private PaperStructureService.BuiltStructure built(PaperTree tree) {
    List<InterviewQuestionDTO> questions = tree.flattenQuestions();
    return new PaperStructureService.BuiltStructure(
        "chinese", tree, questions, 6, List.of());
  }
}
