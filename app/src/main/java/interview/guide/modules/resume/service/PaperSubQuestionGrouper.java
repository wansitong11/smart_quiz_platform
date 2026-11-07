package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将父题和紧随其后的 SUB_QUESTION 节点整理成“题干组 + 小问”结构。
 */
@Component
public class PaperSubQuestionGrouper {

  private static final Pattern SHARED_VISUAL_HINT = Pattern.compile(
      "如图|图中|下图|上图|展开图|所给|如图所示的?图");

  public PaperTree group(PaperTree tree) {
    return group(tree, Map.of());
  }

  public PaperTree group(PaperTree tree, Map<Integer, String> rawTypes) {
    Map<Integer, String> questionTypes = rawTypes == null ? Map.of() : rawTypes;
    PaperNode groupedRoot = groupNode(tree.root(), questionTypes);
    PaperNode root = reindex(groupedRoot, new int[] {0});
    return PaperTree.recalculate(new PaperTree(tree.version(), root));
  }

  private PaperNode groupNode(PaperNode node, Map<Integer, String> rawTypes) {
    List<PaperNode> children = node.safeChildren();
    List<PaperNode> groupedChildren = new ArrayList<>();
    for (int i = 0; i < children.size(); i++) {
      PaperNode parent = children.get(i);
      if (isParentQuestion(parent, rawTypes) && i + 1 < children.size()
          && isSubQuestion(children.get(i + 1), rawTypes)) {
        List<PaperNode> subQuestions = new ArrayList<>();
        int cursor = i + 1;
        while (cursor < children.size() && isSubQuestion(children.get(cursor), rawTypes)) {
          subQuestions.add(children.get(cursor));
          cursor++;
        }
        groupedChildren.add(toQuestionGroup(parent, subQuestions, rawTypes));
        i = cursor - 1;
        continue;
      }
      groupedChildren.add(groupNode(parent, rawTypes));
    }
    return copyWithChildren(node, groupedChildren);
  }

  private PaperNode toQuestionGroup(PaperNode parent, List<PaperNode> subQuestions,
                                    Map<Integer, String> rawTypes) {
    List<String> sharedAssets = new ArrayList<>();
    if (parent.meta() != null && parent.meta().assetIds() != null) {
      sharedAssets.addAll(parent.meta().assetIds());
    }
    String parentText = parent.question().question() == null ? "" : parent.question().question();
    boolean liftChildAssets = sharedAssets.isEmpty()
        && SHARED_VISUAL_HINT.matcher(parentText).find();
    if (liftChildAssets) {
      Set<String> lifted = new LinkedHashSet<>();
      for (PaperNode subQuestion : subQuestions) {
        if (subQuestion.meta() != null && subQuestion.meta().assetIds() != null) {
          lifted.addAll(subQuestion.meta().assetIds());
        }
      }
      sharedAssets.addAll(lifted);
    }

    List<PaperNode> children = new ArrayList<>();
    String parentContent = parent.question().question() == null ? "" : parent.question().question();
    for (PaperNode subQuestion : subQuestions) {
      PaperNode child = groupNode(subQuestion, rawTypes);
      if (child.question() != null && child.question().question() != null) {
        parentContent = parentContent.replace(child.question().question(), "").strip();
      }
      if (liftChildAssets && child.meta() != null) {
        child = withAssetIds(child, List.of());
      }
      children.add(child);
    }
    String title = "第" + (parent.questionIndex() + 1) + "题";
    PaperNode.PaperMeta meta = new PaperNode.PaperMeta(
        null, children.size(), parent.meta() == null ? null : parent.meta().durationMinutes(),
        List.copyOf(sharedAssets),
        parent.meta() == null ? null : parent.meta().startLine(),
        parent.meta() == null ? null : parent.meta().endLine());
    return new PaperNode(parent.id(), PaperNode.TYPE_MATERIAL, parent.order(), parent.depth(),
        false, title, null, parentContent, meta, null, null, children);
  }

  private PaperNode copyWithChildren(PaperNode node, List<PaperNode> children) {
    return new PaperNode(node.id(), node.type(), node.order(), node.depth(), children.isEmpty(),
        node.title(), node.subtitle(), node.content(), node.meta(), node.questionIndex(),
        node.question(), children);
  }

  private PaperNode withAssetIds(PaperNode node, List<String> assetIds) {
    PaperNode.PaperMeta meta = new PaperNode.PaperMeta(
        node.meta().fullScore(), node.meta().questionCount(), node.meta().durationMinutes(),
        assetIds, node.meta().startLine(), node.meta().endLine());
    return new PaperNode(node.id(), node.type(), node.order(), node.depth(), node.leaf(),
        node.title(), node.subtitle(), node.content(), meta, node.questionIndex(),
        node.question(), node.children());
  }

  private boolean isParentQuestion(PaperNode node, Map<Integer, String> rawTypes) {
    return node.question() != null && !isSubQuestion(node, rawTypes);
  }

  private boolean isSubQuestion(PaperNode node, Map<Integer, String> rawTypes) {
    if (node.question() == null) {
      return false;
    }
    String rawType = rawTypes.get(node.questionIndex());
    if (rawType == null) {
      rawType = node.question().type();
    }
    return "SUB_QUESTION".equalsIgnoreCase(rawType) || "BLANK".equalsIgnoreCase(rawType);
  }

  private PaperNode reindex(PaperNode node, int[] nextIndex) {
    InterviewQuestionDTO question = node.question();
    Integer questionIndex = node.questionIndex();
    if (question != null) {
      int index = nextIndex[0]++;
      question = question.withQuestionIndex(index);
      questionIndex = index;
    }
    List<PaperNode> children = new ArrayList<>();
    for (PaperNode child : node.safeChildren()) {
      children.add(reindex(child, nextIndex));
    }
    return new PaperNode(node.id(), node.type(), node.order(), node.depth(), node.leaf(),
        node.title(), node.subtitle(), node.content(), node.meta(), questionIndex,
        question, children);
  }
}
