package interview.guide.modules.quiz.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.ExamDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.QuestionTypeDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SubjectDefinitionDTO;
import interview.guide.modules.quiz.model.QuizCatalogDTO;
import interview.guide.modules.quiz.model.QuizSubjectEntity;
import interview.guide.modules.quiz.model.QuizSubjectRequest;
import interview.guide.modules.quiz.model.QuizSubjectRequest.KnowledgePointInput;
import interview.guide.modules.quiz.model.QuizSubjectRequest.QuestionTypeInput;
import interview.guide.modules.quiz.repository.QuizSubjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("自定义测验科目服务")
class QuizSubjectServiceTest {

  @Mock
  private QuizSubjectRepository repository;

  @Mock
  private InterviewSkillService skillService;

  @Mock
  private OwnerScope ownerScope;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private QuizSubjectService service;

  @BeforeEach
  void setUp() {
    service = new QuizSubjectService(repository, skillService, ownerScope, objectMapper);
    when(ownerScope.currentUserId()).thenReturn(7L);
  }

  private List<ExamDTO> exams() {
    return List.of(
        new ExamDTO("teacher", "中小学教师资格（小学）", "desc", null, false, 1),
        new ExamDTO("other", "其他/自定义", null, null, true, 99));
  }

  private QuizSubjectRequest request() {
    return new QuizSubjectRequest(
        "软件工程基础",
        "覆盖软件工程核心概念",
        "teacher",
        List.of(new KnowledgePointInput("面向对象")),
        List.of(new QuestionTypeInput("SHORT_ANSWER", 10, 7)),
        60,
        "面向对象、设计原则、软件过程");
  }

  private QuizSubjectEntity entity(String code, String configJson) {
    QuizSubjectEntity entity = new QuizSubjectEntity();
    entity.setId(1L);
    entity.setSubjectCode(code);
    entity.setOwnerId(7L);
    entity.setExamCode("teacher");
    entity.setName("软件工程基础");
    entity.setConfigJson(configJson);
    return entity;
  }

  @Nested
  @DisplayName("创建")
  class CreateTests {

    @Test
    @DisplayName("生成 custom- 前缀科目代码并落库归属本人")
    void shouldCreateSubjectWithGeneratedCode() {
      when(skillService.listExams()).thenReturn(exams());
      when(repository.save(any(QuizSubjectEntity.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      QuizCatalogDTO.Subject subject = service.create(request());

      assertThat(subject.code()).startsWith("custom-");
      assertThat(subject.custom()).isTrue();
      assertThat(subject.name()).isEqualTo("软件工程基础");
      assertThat(subject.examCode()).isEqualTo("teacher");
      assertThat(subject.questionTypes())
          .extracting(QuizCatalogDTO.QuestionType::type)
          .containsExactly("SHORT_ANSWER");
      assertThat(subject.outlineText()).isEqualTo("面向对象、设计原则、软件过程");

      ArgumentCaptor<QuizSubjectEntity> captor = ArgumentCaptor.forClass(QuizSubjectEntity.class);
      verify(repository).save(captor.capture());
      assertThat(captor.getValue().getOwnerId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("所属考试不存在时拒绝创建")
    void shouldRejectUnknownExam() {
      when(skillService.listExams()).thenReturn(exams());
      QuizSubjectRequest bad = new QuizSubjectRequest(
          "软件工程基础", null, "no-such-exam",
          List.of(new KnowledgePointInput("面向对象")),
          List.of(new QuestionTypeInput("SHORT_ANSWER", 10, 7)),
          null, null);

      assertThatThrownBy(() -> service.create(bad))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("所属考试不存在");
    }
  }

  @Nested
  @DisplayName("编辑与删除")
  class UpdateDeleteTests {

    @Test
    @DisplayName("编辑只允许本人科目，软删除后仍保留快照语义")
    void shouldUpdateOwnedSubject() throws Exception {
      when(skillService.listExams()).thenReturn(exams());
      String configJson = objectMapper.writeValueAsString(
          new interview.guide.modules.quiz.model.QuizSubjectConfig(
              "旧说明",
              List.of(new interview.guide.modules.quiz.model.QuizSubjectConfig.KnowledgePoint("kp1", "旧考点")),
              List.of(new interview.guide.modules.quiz.model.QuizSubjectConfig.QuestionType("SHORT_ANSWER", 10, 7)),
              60, null, null));
      when(repository.findByOwnerIdAndSubjectCodeAndDeletedAtIsNull(7L, "custom-abc"))
          .thenReturn(Optional.of(entity("custom-abc", configJson)));
      when(repository.save(any(QuizSubjectEntity.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      QuizCatalogDTO.Subject updated = service.update("custom-abc", request());

      assertThat(updated.name()).isEqualTo("软件工程基础");
      assertThat(updated.knowledgePoints())
          .extracting(QuizCatalogDTO.KnowledgePoint::label)
          .containsExactly("面向对象");
    }

    @Test
    @DisplayName("删除他人科目按不存在处理")
    void shouldTreatOthersSubjectAsMissing() {
      when(repository.findByOwnerIdAndSubjectCodeAndDeletedAtIsNull(7L, "custom-abc"))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.delete("custom-abc"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("不存在");
    }
  }

  @Nested
  @DisplayName("复制内置科目")
  class CloneTests {

    @Test
    @DisplayName("复制内置科目为我的科目并保留来源")
    void shouldCloneBuiltInSubject() {
      when(skillService.getSubjectDefinition("cs_os")).thenReturn(new SubjectDefinitionDTO(
          "cs_os",
          "操作系统",
          "后端面试风格",
          "computer",
          List.of(new SkillCategoryDTO("PROCESS_THREAD", "进程与线程", "NORMAL", null, false)),
          List.of(new QuestionTypeDTO("SHORT_ANSWER", 10, 7),
              new QuestionTypeDTO("TRUE_FALSE", 2, 1)),
          60,
          "persona"));
      when(repository.save(any(QuizSubjectEntity.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      QuizCatalogDTO.Subject cloned = service.clone("cs_os");

      assertThat(cloned.custom()).isTrue();
      assertThat(cloned.code()).startsWith("custom-");
      assertThat(cloned.clonedFromCode()).isEqualTo("cs_os");
      assertThat(cloned.name()).isEqualTo("操作系统（我的）");
      assertThat(cloned.questionTypes())
          .extracting(QuizCatalogDTO.QuestionType::type)
          .containsExactly("SHORT_ANSWER", "TRUE_FALSE");
    }
  }
}
