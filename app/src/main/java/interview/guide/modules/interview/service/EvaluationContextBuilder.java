package interview.guide.modules.interview.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批改上下文组装器：把题目、材料原文、图片资源 ID 拼成模型需要的评分依据。
 * 全部为无状态纯函数。
 */
public final class EvaluationContextBuilder {

  private EvaluationContextBuilder() {
  }

  public static String buildQuestionReferenceContext(List<InterviewQuestionDTO> questions) {
    return buildQuestionReferenceContext(questions, null);
  }

  /**
   * 评分依据：每个小节的材料原文只拼一次，题目前的「所属小节」帮助模型定位上下文。
   */
  public static String buildQuestionReferenceContext(List<InterviewQuestionDTO> questions,
                                                      PaperTree paperTree) {
    StringBuilder sb = new StringBuilder();
    java.util.Set<String> emittedContexts = new java.util.LinkedHashSet<>();
    for (InterviewQuestionDTO question : questions) {
      if (paperTree != null) {
        String context = paperTree.contextOf(question.questionIndex());
        if (!context.isBlank() && emittedContexts.add(context)) {
          sb.append(context).append('\n');
        }
      }
      if (!question.hasReferenceMaterial()) {
        continue;
      }
      sb.append("问题").append(question.questionIndex() + 1);
      if (paperTree != null) {
        paperTree.nearestSection(question.questionIndex())
            .map(node -> node.title() != null ? node.title() : "")
            .filter(title -> !title.isBlank())
            .ifPresent(title -> sb.append("（所属小节：").append(title).append('）'));
      }
      sb.append(": ").append(question.question()).append('\n');
      appendIfPresent(sb, "参考答案", question.referenceAnswer());
      appendIfPresent(sb, "图形摘要", question.visualContext());
      if (question.keyPoints() != null && !question.keyPoints().isEmpty()) {
        sb.append("评分要点: ").append(String.join("；", question.keyPoints())).append('\n');
      }
      appendIfPresent(sb, "评分规则", question.scoringRubric());
      sb.append('\n');
    }
    return sb.toString();
  }

  /**
   * 收集每道题相关的图形资源 ID（选项图、题干图、材料图），供视觉批改按题取图。
   */
  public static Map<Integer, List<String>> collectQuestionAssetIds(PaperTree tree) {
    Map<Integer, List<String>> result = new LinkedHashMap<>();
    for (PaperNode node : tree.questionNodes()) {
      if (node.question() == null || node.questionIndex() == null) {
        continue;
      }
      List<String> assetIds = new ArrayList<>();
      if (node.question().options() != null) {
        node.question().options().stream()
            .map(InterviewQuestionDTO.QuestionOption::assetId)
            .filter(EvaluationContextBuilder::hasText)
            .forEach(assetIds::add);
      }
      if (node.meta() != null && node.meta().assetIds() != null) {
        assetIds.addAll(node.meta().assetIds());
      }
      for (PaperNode material : tree.materialsOf(node.questionIndex())) {
        if (material.meta() != null && material.meta().assetIds() != null) {
          assetIds.addAll(material.meta().assetIds());
        }
      }
      result.put(node.questionIndex(), assetIds.stream().distinct().toList());
    }
    return result;
  }

  private static void appendIfPresent(StringBuilder sb, String label, String value) {
    if (hasText(value)) {
      sb.append(label).append(": ").append(value.trim()).append('\n');
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
