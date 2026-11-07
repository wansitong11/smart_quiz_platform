package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.PaperNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构落库前的最后一道确定性校验。
 */
public final class PaperAssetOwnershipValidator {

  private PaperAssetOwnershipValidator() {
  }

  public static List<String> validate(PaperNode root) {
    List<String> warnings = new ArrayList<>();
    Map<Integer, Integer> questionCounts = new HashMap<>();
    Set<String> assetIds = new HashSet<>();
    walk(root, questionCounts, warnings, assetIds);
    questionCounts.entrySet().stream()
        .filter(entry -> entry.getValue() > 1)
        .findFirst()
        .ifPresent(entry -> {
          throw new BusinessException(
              ErrorCode.PAPER_EXTRACTION_FAILED,
              "结构包含重复 questionIndex: " + entry.getKey());
        });
    return List.copyOf(warnings);
  }

  private static void walk(
      PaperNode node,
      Map<Integer, Integer> questionCounts,
      List<String> warnings,
      Set<String> assetIds
  ) {
    if (node == null) {
      return;
    }
    if (node.questionIndex() != null) {
      questionCounts.merge(node.questionIndex(), 1, Integer::sum);
    }
    if (node.meta() != null && node.meta().assetIds() != null) {
      for (String assetId : node.meta().assetIds()) {
        if (!assetIds.add(assetId)) {
          warnings.add("DUPLICATE_ASSET_REFERENCE: 资源 " + assetId + " 被多个节点引用");
        }
      }
    }
    if (PaperNode.TYPE_QUESTION_GROUP.equals(node.type())) {
      validateGroup(node, warnings);
    }
    for (PaperNode child : node.safeChildren()) {
      walk(child, questionCounts, warnings, assetIds);
    }
  }

  private static void validateGroup(PaperNode group, List<String> warnings) {
    boolean hasMaterial = group.safeChildren().stream()
        .anyMatch(child -> PaperNode.TYPE_MATERIAL.equals(child.type()));
    boolean hasQuestion = group.safeChildren().stream()
        .anyMatch(PaperAssetOwnershipValidator::containsQuestion);
    if (!hasMaterial || !hasQuestion) {
      warnings.add("INVALID_QUESTION_GROUP: 题组 " + group.id() + " 必须同时包含材料和题目");
    }
    Integer start = group.meta() == null ? null : group.meta().startLine();
    Integer end = group.meta() == null ? null : group.meta().endLine();
    if (start == null || end == null) {
      return;
    }
    for (PaperNode child : group.safeChildren()) {
      if (child.meta() == null || child.meta().startLine() == null
          || child.meta().endLine() == null) {
        continue;
      }
      if (child.meta().startLine() < start || child.meta().endLine() > end) {
        warnings.add("QUESTION_GROUP_RANGE: 题组 " + group.id()
            + " 的子节点行范围超出题组范围");
      }
    }
  }

  private static boolean containsQuestion(PaperNode node) {
    if (PaperNode.TYPE_QUESTION.equals(node.type())) {
      return true;
    }
    return node.safeChildren().stream()
        .anyMatch(PaperAssetOwnershipValidator::containsQuestion);
  }
}
