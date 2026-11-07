package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("父题与子问分组")
class PaperSubQuestionGrouperTest {

  @Test
  @DisplayName("第8题父题与两个小问合并为题干组，共享图片上移到父题")
  void shouldGroupSubQuestionsAndLiftSharedAsset() {
    PaperNode parent = PaperNode.ofQuestion(1,
        question(7, "QUESTION",
            "8. 如图所示，请完成下面两问。\n(1) 共有______种方法。\n(2) 请在图中补充。",
            null));
    PaperNode sub1 = PaperNode.ofQuestion(2,
        question(8, "SUB_QUESTION", "(1) 共有______种方法。", null));
    PaperNode sub2 = withAsset(PaperNode.ofQuestion(3,
        question(9, "SUB_QUESTION", "(2) 请在图中补充。", null)), "p1a10");
    PaperNode section = PaperNode.section("s1", 1, "知识点2", null,
        List.of(parent, sub1, sub2));
    PaperTree tree = PaperTree.recalculate(new PaperTree(PaperTree.CURRENT_VERSION,
        PaperNode.paper("n0", "math", null, List.of(section))));
    PaperSubQuestionGrouper grouper = new PaperSubQuestionGrouper();

    PaperTree grouped = grouper.group(tree);

    PaperNode sectionNode = grouped.root().safeChildren().getFirst();
    assertThat(sectionNode.safeChildren()).singleElement().satisfies(group -> {
      assertThat(group.type()).isEqualTo(PaperNode.TYPE_MATERIAL);
      assertThat(group.content()).contains("如图所示").doesNotContain("(1)");
      assertThat(group.meta().assetIds()).containsExactly("p1a10");
      assertThat(group.safeChildren()).extracting(PaperNode::questionIndex)
          .containsExactly(0, 1);
      assertThat(group.safeChildren()).allSatisfy(child ->
          assertThat(child.meta().assetIds()).isEmpty());
    });
    assertThat(grouped.flattenQuestions()).extracting(InterviewQuestionDTO::questionIndex)
        .containsExactly(0, 1);
  }

  @Test
  @DisplayName("生产链路通过原始节点类型识别子问并分组")
  void shouldGroupByRawNodeTypes() {
    PaperNode parent = PaperNode.ofQuestion(1,
        question(0, "未分类", "8. 请完成下面两问。", null));
    PaperNode sub1 = PaperNode.ofQuestion(2,
        question(1, "未分类", "(1) 第一问。", null));
    PaperNode sub2 = PaperNode.ofQuestion(3,
        question(2, "未分类", "(2) 第二问。", null));
    PaperNode section = PaperNode.section("s1", 1, "知识点2", null,
        List.of(parent, sub1, sub2));
    PaperTree tree = PaperTree.recalculate(new PaperTree(PaperTree.CURRENT_VERSION,
        PaperNode.paper("n0", "math", null, List.of(section))));

    PaperTree grouped = new PaperSubQuestionGrouper().group(tree,
        Map.of(0, "QUESTION", 1, "SUB_QUESTION", 2, "SUB_QUESTION"));

    PaperNode sectionNode = grouped.root().safeChildren().getFirst();
    assertThat(sectionNode.safeChildren()).singleElement().satisfies(group -> {
      assertThat(group.type()).isEqualTo(PaperNode.TYPE_MATERIAL);
      assertThat(group.safeChildren()).hasSize(2);
    });
    assertThat(grouped.flattenQuestions()).hasSize(2);
  }

  @Test
  @DisplayName("生产链路通过原始节点类型识别 BLANK 子题")
  void shouldGroupBlankByRawNodeTypes() {
    PaperNode parent = PaperNode.ofQuestion(1,
        question(0, "未分类", "2. 看拼音，写字词。", null));
    PaperNode blank = PaperNode.ofQuestion(2,
        question(1, "未分类", "yǔ zhòu ( )", null));
    PaperNode section = PaperNode.section("s1", 1, "字词", null,
        List.of(parent, blank));
    PaperTree tree = PaperTree.recalculate(new PaperTree(PaperTree.CURRENT_VERSION,
        PaperNode.paper("n0", "chinese", null, List.of(section))));

    PaperTree grouped = new PaperSubQuestionGrouper().group(tree,
        Map.of(0, "QUESTION", 1, "BLANK"));

    PaperNode sectionNode = grouped.root().safeChildren().getFirst();
    assertThat(sectionNode.safeChildren()).singleElement()
        .satisfies(group -> assertThat(group.safeChildren()).hasSize(1));
    assertThat(grouped.flattenQuestions()).hasSize(1);
  }

  private InterviewQuestionDTO question(int index, String type, String text, String assetId) {
    List<InterviewQuestionDTO.QuestionOption> options = List.of(
        new InterviewQuestionDTO.QuestionOption("A", "1"),
        new InterviewQuestionDTO.QuestionOption("B", "2"));
    return new InterviewQuestionDTO(
        index, text, type, type, null, null, null, null, false, null,
        "暂缺", List.of(), null, null, 4,
        InterviewQuestionDTO.TYPE_FILL_BLANK, options,
        null, 1, 1, null, null, null, false);
  }

  private PaperNode withAsset(PaperNode node, String assetId) {
    PaperNode.PaperMeta meta = new PaperNode.PaperMeta(
        node.meta().fullScore(), node.meta().questionCount(), node.meta().durationMinutes(),
        List.of(assetId));
    return new PaperNode(node.id(), node.type(), node.order(), node.depth(), node.leaf(),
        node.title(), node.subtitle(), node.content(), meta, node.questionIndex(),
        node.question(), node.children());
  }
}
