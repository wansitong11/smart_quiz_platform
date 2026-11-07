package interview.guide.modules.resume.service;

import interview.guide.common.async.TestBoundedExecutor;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;
import interview.guide.modules.resume.service.BlockTreeDTO.BlockOptionDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("阅读文章与重复段落题目的结构归组")
class PaperReadingMaterialStructureTest {

  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private PaperPageRepository pageRepository;
  @Mock
  private PaperAssetService assetService;
  @Mock
  private LlmProviderRegistry providerRegistry;
  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;
  @Mock
  private TransactionalExecutor transactionalExecutor;
  @Mock
  private PaperAnalysisPacer analysisPacer;

  @Test
  @DisplayName("重复出现的段落摘录应归入完整文章，而不是拆成多个根材料")
  void shouldKeepQuestionsUnderTheCompleteReadingPassage() {
    List<String> lines = List.of(
        "DEER POPULATIONS OF THE PUGET SOUND",
        "Two species of deer have been prevalent in the Puget Sound area,",
        "The black-tailed deer is now [[u:restricted]] to the lowlands.",
        "Nearly any kind of plant can be part of a deer's diet.",
        "What keeps the deer alive in winter is the urge to migrate.",
        "第一段",
        "Two species of deer have been prevalent in the Puget Sound area,",
        "The black-tailed deer is now restricted to the lowlands.",
        "第 1 段",
        "1. According to paragraph 1, which statement is true?",
        "(A) It is native to lowlands.",
        "(B) It no longer lives in one habitat.",
        "(C) It replaced another deer.",
        "(D) It is unrelated to other deer.",
        "Paragraph 2",
        "Nearly any kind of plant can be part of a deer's diet.",
        "What keeps the deer alive in winter is the urge to migrate.",
        "2. According to paragraph 2, why do deer migrate?",
        "(A) To hibernate.",
        "(B) To find food.",
        "(C) To avoid rain.",
        "(D) To remain in highlands."
    );
    BlockTreeDTO blocks = new BlockTreeDTO("english", 6, List.of(
        material(1, 3, "DEER POPULATIONS OF THE PUGET SOUND"),
        material(4, 5, "阅读材料片段"),
        material(6, 9, "第一段"),
        question(10, 14, "1. According to paragraph 1, which statement is true?"),
        material(15, 17, "Paragraph 2"),
        question(18, 22, "2. According to paragraph 2, why do deer migrate?")
    ), List.of());
    PaperStructureService service = service();
    BlockTreeDTO normalized = ReflectionTestUtils.invokeMethod(
        service,
        "normalizeBlocks",
        blocks,
        lines
    );

    PaperStructureService.BuiltStructure built = ReflectionTestUtils.invokeMethod(
        service,
        "assemble",
        1L,
        lines,
        List.of(new int[] {1, 1, lines.size()}),
        normalized,
        List.of(),
        Map.of()
    );

    assertThat(built).isNotNull();
    assertThat(built.paperTree().root().children()).hasSize(1);
    PaperNode questionGroup = built.paperTree().root().children().getFirst();
    assertThat(questionGroup.type()).isEqualTo(PaperNode.TYPE_QUESTION_GROUP);
    PaperNode readingMaterial = questionGroup.children().stream()
        .filter(node -> PaperNode.TYPE_MATERIAL.equals(node.type()))
        .findFirst()
        .orElseThrow();
    assertThat(readingMaterial.type()).isEqualTo(PaperNode.TYPE_MATERIAL);
    assertThat(readingMaterial.title()).isEqualTo("DEER POPULATIONS OF THE PUGET SOUND");
    assertThat(questionGroup.children().stream()
        .filter(node -> PaperNode.TYPE_QUESTION.equals(node.type())).toList())
        .extracting(PaperNode::questionIndex)
        .containsExactly(0, 1);
    assertThat(readingMaterial.content())
        .contains("Two species of deer", "urge to migrate")
        .doesNotContain("第 1 段");
    assertThat(built.paperTree().materialsOf(0))
        .singleElement()
        .isSameAs(readingMaterial);
  }

  @Test
  @DisplayName("相邻但独立的第二篇文章不能被误合并")
  void shouldKeepIndependentAdjacentPassages() {
    List<String> lines = List.of(
        "PASSAGE ONE",
        "The first passage ends with a complete sentence.",
        "PASSAGE TWO",
        "The second passage starts a different reading task."
    );
    List<BlockNodeDTO> normalized = PaperMaterialHierarchyNormalizer.normalize(
        List.of(
            material(1, 2, "PASSAGE ONE"),
            material(3, 4, "PASSAGE TWO")
        ),
        lines
    );

    assertThat(normalized).extracting(BlockNodeDTO::type).containsExactly("MATERIAL", "MATERIAL");
    assertThat(normalized).extracting(BlockNodeDTO::title)
        .containsExactly("PASSAGE ONE", "PASSAGE TWO");
  }

  private PaperStructureService service() {
    return new PaperStructureService(
        resumeRepository,
        pageRepository,
        assetService,
        new PaperSubQuestionGrouper(),
        new PaperAssetOwnershipResolver(),
        new PaperAssetBindingService(assetService, new OptionRowSplitter()),
        new PaperQuestionGroupAssembler(),
        providerRegistry,
        structuredOutputInvoker,
        transactionalExecutor,
        new ObjectMapper(),
        new DefaultResourceLoader(),
        analysisPacer,
        TestBoundedExecutor.create(1, 20)
    );
  }

  private BlockNodeDTO material(int startLine, int endLine, String title) {
    return new BlockNodeDTO(
        "MATERIAL", startLine, endLine, title, null, null, List.of(),
        null, List.of(), null, null, null, List.of(), null
    );
  }

  private BlockNodeDTO question(int startLine, int endLine, String title) {
    return new BlockNodeDTO(
        "QUESTION", startLine, endLine, title, "single_choice", 3,
        List.of(
            new BlockOptionDTO("A", "Option A"),
            new BlockOptionDTO("B", "Option B"),
            new BlockOptionDTO("C", "Option C"),
            new BlockOptionDTO("D", "Option D")
        ),
        null, List.of(), null, null, null, List.of(), "TEXT"
    );
  }
}
