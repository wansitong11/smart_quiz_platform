package interview.guide.modules.quiz.service;

import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.ExamDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.QuestionTypeDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SubjectDefinitionDTO;
import interview.guide.modules.quiz.model.QuizCatalogDTO;
import interview.guide.modules.quiz.model.QuizSubjectSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("测验目录聚合")
class QuizCatalogServiceTest {

  @Mock
  private InterviewSkillService skillService;

  @Mock
  private QuizSubjectService subjectService;

  private QuizCatalogService service;

  @BeforeEach
  void setUp() {
    service = new QuizCatalogService(skillService, subjectService);
  }

  private List<ExamDTO> exams() {
    return List.of(
        new ExamDTO("computer", "计算机专业能力（后端方向）", "desc", null, false, 3),
        new ExamDTO("general", "中小学/学科练习", null, null, false, 90),
        new ExamDTO("other", "其他/自定义", null, null, true, 99));
  }

  @Test
  @DisplayName("系统科目与我的科目合并进对应考试分组")
  void shouldMergeSystemAndCustomSubjectsIntoExamGroups() {
    when(skillService.listExams()).thenReturn(exams());
    when(skillService.listSubjectDefinitions()).thenReturn(List.of(
        new SubjectDefinitionDTO(
            "cs_os", "操作系统", "desc", "computer",
            List.of(new SkillCategoryDTO("PROCESS_THREAD", "进程与线程", "NORMAL", null, false)),
            List.of(new QuestionTypeDTO("SHORT_ANSWER", 10, 7)),
            60, "persona")));
    when(subjectService.listMine()).thenReturn(List.of(
        new QuizCatalogDTO.Subject(
            "custom-1", "软考刷题", null, "computer", true,
            List.of(new QuizCatalogDTO.KnowledgePoint("kp1", "项目管理")),
            List.of(new QuizCatalogDTO.QuestionType("SINGLE_CHOICE", 2, 3)),
            45, null, null)));

    QuizCatalogDTO catalog = service.catalog();

    assertThat(catalog.exams())
        .extracting(QuizCatalogDTO.Exam::code)
        .containsExactly("computer", "general", "other");
    QuizCatalogDTO.Exam computer = catalog.exams().get(0);
    assertThat(computer.subjects())
        .extracting(QuizCatalogDTO.Subject::code)
        .containsExactly("cs_os", "custom-1");
    assertThat(computer.subjects().get(1).custom()).isTrue();
  }

  @Test
  @DisplayName("解析内置科目时带考试名与 persona")
  void shouldResolveBuiltInSubjectSpec() {
    when(skillService.listExams()).thenReturn(exams());
    when(skillService.getSubjectDefinition("cs_os")).thenReturn(new SubjectDefinitionDTO(
        "cs_os", "操作系统", "desc", "computer",
        List.of(new SkillCategoryDTO("PROCESS_THREAD", "进程与线程", "NORMAL", null, false)),
        List.of(new QuestionTypeDTO("SHORT_ANSWER", 10, 7)),
        60, "persona"));

    QuizSubjectSpec spec = service.resolveSubject("cs_os");

    assertThat(spec.examName()).isEqualTo("计算机专业能力（后端方向）");
    assertThat(spec.persona()).isEqualTo("persona");
    assertThat(spec.categories())
        .extracting(QuizSubjectSpec.Category::label)
        .containsExactly("进程与线程");
  }

  @Test
  @DisplayName("解析自定义科目时注入大纲文本且 persona 为空")
  void shouldResolveCustomSubjectSpecWithOutline() {
    when(skillService.listExams()).thenReturn(exams());
    when(skillService.getSubjectDefinition("custom-1")).thenReturn(null);
    when(subjectService.get("custom-1")).thenReturn(new QuizCatalogDTO.Subject(
        "custom-1", "软考刷题", null, "other", true,
        List.of(new QuizCatalogDTO.KnowledgePoint("kp1", "项目管理")),
        List.of(new QuizCatalogDTO.QuestionType("SINGLE_CHOICE", 2, 3)),
        45, null, "软考高级大纲"));

    QuizSubjectSpec spec = service.resolveSubject("custom-1");

    assertThat(spec.examName()).isEqualTo("其他/自定义");
    assertThat(spec.persona()).isNull();
    assertThat(spec.outlineText()).isEqualTo("软考高级大纲");
  }
}
