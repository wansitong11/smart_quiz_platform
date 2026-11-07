package interview.guide.modules.interview.service;

import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewPersistenceServiceTest {

  @Mock
  private InterviewSessionRepository sessionRepository;

  @Mock
  private InterviewAnswerRepository answerRepository;

  @Mock
  private ResumeRepository resumeRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("知识库面试保存时写入 subjectCategory")
  void shouldSaveSubjectCategoryForKnowledgeBaseSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(PracticeSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid1", null, 1, PaperTree.fromFlatQuestions(List.of(), "测试"), "dashscope",
        "knowledge-base", "mid", "KNOWLEDGE_BASE", 9L, "MySQL");

    ArgumentCaptor<PracticeSessionEntity> captor = ArgumentCaptor.forClass(PracticeSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    PracticeSessionEntity saved = captor.getValue();
    assertThat(saved.getSubjectCategory()).isEqualTo("MySQL");
    assertThat(saved.getKnowledgeBaseId()).isEqualTo(9L);
    assertThat(saved.getSourceType()).isEqualTo("KNOWLEDGE_BASE");
  }

  @Test
  @DisplayName("普通面试保存时 subjectCategory 保持 null")
  void shouldKeepSubjectCategoryNullForNormalSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(PracticeSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid2", null, 1, PaperTree.fromFlatQuestions(List.of(), "测试"), "dashscope", "chinese", "mid");

    ArgumentCaptor<PracticeSessionEntity> captor = ArgumentCaptor.forClass(PracticeSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    PracticeSessionEntity saved = captor.getValue();
    assertThat(saved.getSubjectCategory()).isNull();
    assertThat(saved.getSourceType()).isEqualTo("NORMAL");
    assertThat(saved.getKnowledgeBaseId()).isNull();
  }

  @Test
  @DisplayName("试卷会话保存时冻结当前内容代次")
  void shouldFreezePaperContentRevisionForPaperSession() {
    InterviewPersistenceService service = newService();
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setId(7L);
    paper.setContentRevision(42L);
    when(resumeRepository.findById(7L)).thenReturn(Optional.of(paper));
    when(sessionRepository.save(any(PracticeSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession(
        "paper-session",
        7L,
        1,
        PaperTree.fromFlatQuestions(List.of(), "测试"),
        "dashscope",
        "chinese",
        "mid",
        "PAPER",
        null,
        "chinese"
    );

    ArgumentCaptor<PracticeSessionEntity> captor =
        ArgumentCaptor.forClass(PracticeSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    assertThat(captor.getValue().getPaperContentRevision()).isEqualTo(42L);
  }

  @Test
  @DisplayName("报告序列化失败时抛业务异常并阻止落库")
  void shouldRollbackWhenReportSerializationFails() throws Exception {
    ObjectMapper failingMapper = mock(ObjectMapper.class);
    when(failingMapper.writeValueAsString(any()))
        .thenThrow(mock(JacksonException.class));
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setSessionId("report-session");
    when(sessionRepository.findBySessionId("report-session"))
        .thenReturn(Optional.of(session));

    InterviewReportDTO report = new InterviewReportDTO(
        "report-session",
        1,
        80,
        5,
        4,
        List.of(),
        List.of(new InterviewReportDTO.QuestionEvaluation(
            0, "题目", "数学", "A", 80, "不错", 5, 4,
            InterviewReportDTO.ScoreStatus.AUTO, false, 95, null)),
        "整体不错",
        List.of("优点"),
        List.of("改进"),
        List.of(new InterviewReportDTO.ReferenceAnswer(
            0, "题目", "A", List.of("要点")))
    );

    assertThatThrownBy(() -> newService(failingMapper).saveReport("report-session", report))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("报告序列化失败");
    verify(sessionRepository, never()).save(any());
  }

  private InterviewPersistenceService newService() {
    return newService(objectMapper);
  }

  private InterviewPersistenceService newService(ObjectMapper mapper) {
    return new InterviewPersistenceService(
        new OwnerScope(),
        sessionRepository,
        answerRepository,
        resumeRepository,
        mapper
    );
  }
}
