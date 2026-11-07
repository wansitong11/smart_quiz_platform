package interview.guide.modules.interview.skill;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.interview.skill.InterviewSkillService.ExamDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.QuestionTypeDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SubjectDefinitionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("技能与考试目录加载")
class InterviewSkillServiceCatalogTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;

  @Mock
  private PromptSanitizer promptSanitizer;

  private InterviewSkillService service;

  @BeforeEach
  void setUp() throws IOException {
    service = new InterviewSkillService(
        llmProviderRegistry, structuredOutputInvoker, new DefaultResourceLoader(), promptSanitizer);
    service.loadPresetSkills();
  }

  @Test
  @DisplayName("考试分组包含三个考证考试、归档分组与自定义桶")
  void shouldLoadExamGroups() {
    assertThat(service.listExams())
        .extracting(ExamDTO::code)
        .contains("teacher", "accounting", "computer", "general", "other");
  }

  @Test
  @DisplayName("八个考证科目挂到对应考试并带题型默认分与权重")
  void shouldLoadCertificationSubjects() {
    Map<String, SubjectDefinitionDTO> subjects = service.listSubjectDefinitions().stream()
        .collect(Collectors.toMap(SubjectDefinitionDTO::id, Function.identity()));

    assertThat(subjects.keySet()).contains(
        "teacher_quality", "teacher_pedagogy",
        "accounting_practice", "accounting_law",
        "cs_data_structure", "cs_network", "cs_os", "cs_database");

    SubjectDefinitionDTO csOs = subjects.get("cs_os");
    assertThat(csOs.examCode()).isEqualTo("computer");
    assertThat(csOs.questionTypes())
        .extracting(QuestionTypeDTO::type)
        .containsExactly("SHORT_ANSWER", "SINGLE_CHOICE", "TRUE_FALSE");
    assertThat(csOs.questionTypes().get(0).weight()).isEqualTo(7);
    assertThat(csOs.questionTypes().get(0).defaultScore()).isEqualTo(10);

    SubjectDefinitionDTO practice = subjects.get("accounting_practice");
    assertThat(practice.examCode()).isEqualTo("accounting");
    assertThat(practice.questionTypes())
        .extracting(QuestionTypeDTO::type)
        .containsExactly("SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE");
  }

  @Test
  @DisplayName("旧语数英归档到 general 分组且带题型配置")
  void shouldArchiveLegacySubjects() {
    List<SubjectDefinitionDTO> general = service.listSubjectDefinitions().stream()
        .filter(subject -> "general".equals(subject.examCode()))
        .toList();

    assertThat(general)
        .extracting(SubjectDefinitionDTO::id)
        .containsExactlyInAnyOrder("chinese", "math", "english");
    assertThat(general).allSatisfy(subject ->
        assertThat(subject.questionTypes()).isNotEmpty());
  }
}
