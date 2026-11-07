package interview.guide.modules.quiz.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.quiz.config.QuizProperties;
import interview.guide.modules.quiz.model.WrongQuestionEntity;
import interview.guide.modules.quiz.model.WrongQuestionSnapshot;
import interview.guide.modules.quiz.model.WrongQuestionStatus;
import interview.guide.modules.quiz.repository.WrongQuestionAttemptEventRepository;
import interview.guide.modules.quiz.repository.WrongQuestionBackfillRepository;
import interview.guide.modules.quiz.repository.WrongQuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("错题本服务：回填与批改回写边界")
class WrongQuestionBookServiceTest {

  @Mock
  private WrongQuestionRepository repository;
  @Mock
  private WrongQuestionBackfillRepository backfillRepository;
  @Mock
  private WrongQuestionAttemptEventRepository attemptEventRepository;
  @Mock
  private InterviewPersistenceService persistenceService;
  @Mock
  private InterviewSessionService sessionService;
  @Mock
  private QuizCatalogService catalogService;
  @Mock
  private OwnerScope ownerScope;

  private ObjectMapper objectMapper;
  private WrongQuestionBookService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    service = new WrongQuestionBookService(repository, backfillRepository,
        attemptEventRepository, persistenceService, sessionService, catalogService,
        new QuizProperties(), ownerScope, objectMapper);
  }

  @Test
  @DisplayName("历史回填不会把用户手动移出的错题恢复为待复习")
  void keepsArchivedStatusWhenBackfillReplaysHistory() throws Exception {
    InterviewQuestionDTO question = question("同一道题");
    PaperTree tree = PaperTree.fromFlatQuestions(List.of(question), "错题回填");
    PracticeSessionEntity session = gradedSession("s1", 42L);
    PracticeAnswerEntity answer = answer(0, 0);
    WrongQuestionEntity entity = archivedEntity(question, tree);
    LocalDateTime archivedAt = entity.getArchivedAt();

    when(ownerScope.effectiveOwnerId()).thenReturn(1L);
    when(backfillRepository.existsById(1L)).thenReturn(false);
    when(repository.findByOwnerId(1L)).thenReturn(List.of(entity));
    when(persistenceService.findSessionsByOwnerId(1L)).thenReturn(List.of(session));
    when(persistenceService.findPaperTree("s1")).thenReturn(Optional.of(tree));
    when(persistenceService.findAnswersBySessionId("s1")).thenReturn(List.of(answer));
    when(attemptEventRepository.existsBySessionIdAndQuestionIndex("s1", 0))
        .thenReturn(false);
    when(repository.findAll(
        ArgumentMatchers.<Specification<WrongQuestionEntity>>any(),
        any(PageRequest.class)))
        .thenReturn(Page.empty());
    when(repository.count(
        ArgumentMatchers.<Specification<WrongQuestionEntity>>any())).thenReturn(0L);
    when(repository.countBySubject(1L)).thenReturn(List.of());

    service.list(null, 0, 20);

    assertThat(entity.getStatus()).isEqualTo(WrongQuestionStatus.ARCHIVED);
    assertThat(entity.getArchivedAt()).isEqualTo(archivedAt);
    assertThat(entity.getWrongCount()).isEqualTo(1);
    verify(repository).saveAll(any());
  }

  @Test
  @DisplayName("混合来源重练没有会话级 paperId 时保留错题原图来源")
  void preservesOriginalPaperIdWhenRetrySessionHasNoPaperId() throws Exception {
    InterviewQuestionDTO question = question("带原图的题");
    PaperNode questionNode = new PaperNode(
        "q1", PaperNode.TYPE_QUESTION, 1, 0, true, null, null, null,
        new PaperNode.PaperMeta(5, 1, null, List.of("asset-1")),
        0, question, List.of());
    PaperTree tree = PaperTree.recalculate(new PaperTree(
        PaperTree.CURRENT_VERSION,
        PaperNode.paper("n0", "错题重练", null,
            List.of(PaperNode.section("s1", 1, "错题重练", null,
                List.of(questionNode))))));
    PracticeSessionEntity session = gradedSession("s2", null);
    PracticeAnswerEntity answer = answer(0, 100);
    WrongQuestionSnapshot oldSnapshot = new WrongQuestionSnapshot(
        question, "错题重练", null, List.of(), 99L, List.of("asset-1"));
    WrongQuestionEntity entity = entity(question, oldSnapshot);

    when(persistenceService.findBySessionIdForUpdate("s2")).thenReturn(Optional.of(session));
    when(persistenceService.findPaperTree("s2")).thenReturn(Optional.of(tree));
    when(persistenceService.findAnswersBySessionId("s2")).thenReturn(List.of(answer));
    when(attemptEventRepository.existsBySessionIdAndQuestionIndex("s2", 0))
        .thenReturn(false);
    when(repository.findForUpdate(1L, entity.getQuestionKey()))
        .thenReturn(Optional.of(entity));

    service.recordAttemptResults("s2");

    WrongQuestionSnapshot saved = objectMapper.readValue(
        entity.getQuestionSnapshot(), WrongQuestionSnapshot.class);
    assertThat(saved.paperId()).isEqualTo(99L);
    assertThat(saved.assetIds()).containsExactly("asset-1");
    assertThat(entity.getCorrectCount()).isEqualTo(1);
    verify(repository).save(entity);
  }

  private WrongQuestionEntity archivedEntity(InterviewQuestionDTO question, PaperTree tree)
      throws Exception {
    WrongQuestionEntity entity = entity(question, new WrongQuestionSnapshot(
        question, "错题回填", null, List.of(), 42L, List.of()));
    entity.archive(LocalDateTime.of(2026, 10, 20, 9, 0));
    return entity;
  }

  private WrongQuestionEntity entity(InterviewQuestionDTO question,
                                     WrongQuestionSnapshot snapshot) throws Exception {
    WrongQuestionEntity entity = new WrongQuestionEntity();
    entity.setOwnerId(1L);
    entity.setSkillId("math");
    entity.setQuestionKey(WrongQuestionFingerprint.of("math", question, snapshot));
    entity.setStem(question.question());
    entity.setStatus(WrongQuestionStatus.PENDING);
    entity.setQuestionSnapshot(objectMapper.writeValueAsString(snapshot));
    return entity;
  }

  private PracticeSessionEntity gradedSession(String sessionId, Long resumeId) {
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setSessionId(sessionId);
    session.setOwnerId(1L);
    session.setSkillId("math");
    session.setSourceType("QUIZ");
    session.setStatus(PracticeSessionEntity.SessionStatus.EVALUATED);
    session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    session.setCreatedAt(LocalDateTime.of(2026, 10, 21, 10, 0));
    ReflectionTestUtils.setField(session, "resumeId", resumeId);
    return session;
  }

  private PracticeAnswerEntity answer(int questionIndex, int score) {
    PracticeAnswerEntity answer = new PracticeAnswerEntity();
    answer.setQuestionIndex(questionIndex);
    answer.setScore(score);
    answer.setAnsweredAt(LocalDateTime.of(2026, 10, 21, 10, 5));
    return answer;
  }

  private InterviewQuestionDTO question(String stem) {
    return new InterviewQuestionDTO(
        0, stem, "kp", "知识点", null, null, null, null, false, null,
        "A", List.of(), null, null, 5, InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(new InterviewQuestionDTO.QuestionOption("A", "甲")),
        "解析", null, null, null, null, null, false);
  }
}
