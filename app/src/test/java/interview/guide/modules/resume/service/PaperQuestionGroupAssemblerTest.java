package interview.guide.modules.resume.service;

import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("材料题组确定性组装")
class PaperQuestionGroupAssemblerTest {

  private final PaperQuestionGroupAssembler assembler = new PaperQuestionGroupAssembler();

  @Test
  @DisplayName("连续材料和连续题目生成一个共享题组")
  void groupsConsecutiveMaterialsAndQuestions() {
    PaperQuestionGroupAssembler.GroupAssembly assembly = assembler.assemble(List.of(
        material(58, 60, "材料一"),
        material(61, 64, "材料二"),
        question(65, 70),
        question(71, 76)
    ));

    assertThat(assembly.plans()).singleElement().satisfies(plan -> {
      assertThat(plan.startLine()).isEqualTo(58);
      assertThat(plan.endLine()).isEqualTo(76);
      assertThat(plan.materials()).hasSize(2);
      assertThat(plan.questions()).hasSize(2);
    });
  }

  @Test
  @DisplayName("没有材料的独立题不生成空题组")
  void doesNotCreateEmptyGroups() {
    assertThat(assembler.assemble(List.of(question(1, 4), question(5, 8))).plans()).isEmpty();
  }

  @Test
  @DisplayName("题目后出现新材料时关闭旧组并开启新组")
  void newMaterialAfterQuestionsStartsNewGroup() {
    PaperQuestionGroupAssembler.GroupAssembly assembly = assembler.assemble(List.of(
        material(1, 2, "材料一"),
        question(3, 4),
        material(5, 6, "材料二"),
        question(7, 8)
    ));

    assertThat(assembly.plans()).hasSize(2);
    assertThat(assembly.plans()).extracting(QuestionGroupPlan::startLine)
        .containsExactly(1, 5);
  }

  @Test
  @DisplayName("EXCERPT 不切断材料包")
  void excerptDoesNotBreakGroup() {
    PaperQuestionGroupAssembler.GroupAssembly assembly = assembler.assemble(List.of(
        material(1, 2, "材料一"),
        new BlockNodeDTO("EXCERPT", 3, 3, "摘录", null, null, List.of(),
            null, List.of(), null, null, null, List.of(), null),
        material(4, 5, "材料二"),
        question(6, 7)
    ));

    assertThat(assembly.plans()).singleElement().satisfies(plan ->
        assertThat(plan.materials()).hasSize(2));
  }

  @Test
  @DisplayName("SECTION 边界强制关闭题组")
  void sectionBoundaryClosesGroup() {
    PaperQuestionGroupAssembler.GroupAssembly assembly = assembler.assemble(List.of(
        material(1, 2, "材料一"),
        question(3, 4),
        new BlockNodeDTO("SECTION", 5, 9, "新小节", null, null, List.of(),
            null, List.of(), null, null, null, List.of(), null),
        question(10, 12)
    ));

    assertThat(assembly.plans()).singleElement();
  }

  private BlockNodeDTO material(int start, int end, String title) {
    return new BlockNodeDTO("MATERIAL", start, end, title, null, null, List.of(),
        null, List.of(), null, null, null, List.of(), null);
  }

  private BlockNodeDTO question(int start, int end) {
    return new BlockNodeDTO("QUESTION", start, end, null, "single_choice", 3,
        List.of(), null, List.of(), null, null, null, List.of(), "TEXT");
  }
}
