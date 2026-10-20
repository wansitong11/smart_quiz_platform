package interview.guide.modules.interview.model;

import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("试卷层级树")
class PaperTreeTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private InterviewQuestionDTO choiceQuestion(int index, String question, int fullScore) {
    return new InterviewQuestionDTO(
        index, question, "SINGLE_CHOICE", "现代文阅读", null, null, null, null, false, null,
        "A", List.of("要点一"), "选对得满分", null, fullScore, InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        List.of(new QuestionOption("A", "选项甲"), new QuestionOption("B", "选项乙")),
        "甲项与材料一致。", null, null
    );
  }

  private PaperTree sampleTree() {
    PaperNode first = PaperNode.section("n1", 1, "一、现代文阅读", null, List.of(
        PaperNode.material("m1", 1, "材料一", "中华文明源远流长……"),
        PaperNode.ofQuestion(2, choiceQuestion(0, "第 1 题", 3))
    ));
    PaperNode second = PaperNode.section("n2", 2, "（一）现代文阅读 II", null, List.of(
        PaperNode.ofQuestion(1, choiceQuestion(1, "第 2 题", 4))
    ));
    return PaperTree.recalculate(new PaperTree(PaperTree.CURRENT_VERSION,
        PaperNode.paper("n0", "语文模拟卷", null, List.of(first, second))));
  }

  @Nested
  @DisplayName("扁平视图")
  class FlattenTests {

    @Test
    @DisplayName("按 questionIndex 升序派生题目列表，且返回可变列表")
    void flattensQuestionsInIndexOrder() {
      List<InterviewQuestionDTO> questions = sampleTree().flattenQuestions();

      assertThat(questions).extracting(InterviewQuestionDTO::questionIndex).containsExactly(0, 1);
      assertThat(questions).extracting(InterviewQuestionDTO::question).containsExactly("第 1 题", "第 2 题");

      // 批改与作答链路会在该列表上写回答案，必须是可变的
      questions.set(0, questions.get(0).withAnswer("A"));
      assertThat(questions.get(0).userAnswer()).isEqualTo("A");
    }

    @Test
    @DisplayName("回写题目载荷不改变层级与材料")
    void withQuestionsKeepsHierarchy() {
      PaperTree tree = sampleTree();
      PaperTree updated = tree.withQuestions(
          List.of(choiceQuestion(0, "第 1 题", 3).withEvaluation(3, "答对"),
              choiceQuestion(1, "第 2 题", 4)));

      assertThat(updated.questionNode(0).orElseThrow().question().score()).isEqualTo(3);
      assertThat(updated.questionNode(0).orElseThrow().question().feedback()).isEqualTo("答对");
      assertThat(updated.materialsOf(0)).extracting(PaperNode::title).containsExactly("材料一");
      assertThat(updated.root().meta().fullScore()).isEqualTo(7);
      assertThat(updated.flattenQuestions()).extracting(InterviewQuestionDTO::questionType)
          .containsOnly(InterviewQuestionDTO.TYPE_SINGLE_CHOICE);
    }
  }

  @Nested
  @DisplayName("结构与展示")
  class StructureTests {

    @Test
    @DisplayName("同级 order 重排后按 order 排序，depth / leaf 由后端回写")
    void recalculatesOrderDepthAndLeaf() {
      PaperNode late = PaperNode.section("n2", 5, "（二）现代文阅读 II", null, List.of(
          PaperNode.ofQuestion(1, choiceQuestion(1, "第 2 题", 4))));
      PaperNode early = PaperNode.section("n1", 2, "（一）现代文阅读 I", null, List.of(
          PaperNode.ofQuestion(1, choiceQuestion(0, "第 1 题", 3))));

      PaperTree tree = PaperTree.recalculate(new PaperTree(PaperTree.CURRENT_VERSION,
          PaperNode.paper("n0", "语文模拟卷", null, List.of(late, early))));

      assertThat(tree.root().safeChildren()).extracting(PaperNode::title)
          .containsExactly("（一）现代文阅读 I", "（二）现代文阅读 II");
      assertThat(tree.root().safeChildren()).extracting(PaperNode::depth).containsOnly(1);
      assertThat(tree.questionNode(0).orElseThrow().depth()).isEqualTo(2);
      assertThat(tree.root().meta().questionCount()).isEqualTo(2);
      assertThat(tree.root().meta().fullScore()).isEqualTo(7);
    }

    @Test
    @DisplayName("批改上下文带小节标题与材料原文")
    void buildsContextFromSectionAndMaterial() {
      PaperTree tree = sampleTree();

      String context = tree.contextOf(0);

      assertThat(context).contains("【小节】一、现代文阅读").contains("【材料】材料一")
          .contains("中华文明源远流长");
      assertThat(tree.sectionPathOf(0)).isEqualTo("一、现代文阅读");
      assertThat(tree.nearestSection(0).orElseThrow().title()).isEqualTo("一、现代文阅读");
      // 第二节没有材料，不应把上一节的阅读材料带进去
      assertThat(tree.materialsOf(1)).isEmpty();
    }

    @Test
    @DisplayName("题目之后出现的材料不算作该题上下文")
    void ignoresMaterialPlacedAfterQuestion() {
      PaperNode section = PaperNode.section("n1", 1, "一、现代文阅读", null, List.of(
          PaperNode.ofQuestion(1, choiceQuestion(0, "第 1 题", 3)),
          PaperNode.material("m1", 2, "材料一", "后置材料")
      ));
      PaperTree tree = PaperTree.recalculate(new PaperTree(PaperTree.CURRENT_VERSION,
          PaperNode.paper("n0", "语文模拟卷", null, List.of(section))));

      assertThat(tree.materialsOf(0)).isEmpty();
      assertThat(tree.contextOf(0)).doesNotContain("后置材料");
    }

    @Test
    @DisplayName("序列化后重新解析保持结构一致（存储只存树）")
    void roundTripsThroughJson() {
      PaperTree tree = sampleTree();

      PaperTree parsed = PaperTree.parse(objectMapper, tree.toJson(objectMapper));

      assertThat(parsed.version()).isEqualTo(PaperTree.CURRENT_VERSION);
      assertThat(parsed.flattenQuestions()).extracting(InterviewQuestionDTO::questionIndex)
          .containsExactlyElementsOf(tree.flattenQuestions().stream()
              .map(InterviewQuestionDTO::questionIndex).toList());
      assertThat(parsed.questionNode(0).orElseThrow().question().question()).isEqualTo("第 1 题");
    }
  }
}
