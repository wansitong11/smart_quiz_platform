package interview.guide.modules.resume.service;

import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把被有分大题覆盖的无分空位/子问折叠进父题，并对同范围节点去重。
 */
final class PaperSubQuestionFoldNormalizer {

  private static final Set<String> QUESTION_LIKE_TYPES =
      Set.of("QUESTION", "SUB_QUESTION", "BLANK");

  private PaperSubQuestionFoldNormalizer() {
  }

  static List<BlockNodeDTO> normalize(List<BlockNodeDTO> nodes, List<String> lines) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    List<BlockNodeDTO> sorted = nodes.stream()
        .filter(node -> node != null)
        .sorted(Comparator.comparingInt(BlockNodeDTO::startLine)
            .thenComparingInt(BlockNodeDTO::endLine))
        .toList();

    Map<String, BlockNodeDTO> unique = new LinkedHashMap<>();
    for (BlockNodeDTO node : sorted) {
      String key = dedupeKey(node);
      if (key == null) {
        unique.put("node-" + unique.size(), node);
      } else {
        unique.putIfAbsent(key, node);
      }
    }
    List<BlockNodeDTO> deduped = new ArrayList<>(unique.values());
    List<BlockNodeDTO> questions = deduped.stream()
        .filter(node -> "QUESTION".equals(normalizeType(node.type())))
        .toList();

    List<BlockNodeDTO> result = new ArrayList<>(deduped.size());
    for (BlockNodeDTO node : deduped) {
      String type = normalizeType(node.type());
      if (("SUB_QUESTION".equals(type) || "BLANK".equals(type))
          && isUnscored(node)
          && coveredByQuestion(node, questions)) {
        continue;
      }
      result.add(node);
    }
    return List.copyOf(result);
  }

  private static String dedupeKey(BlockNodeDTO node) {
    String type = normalizeType(node.type());
    if (!QUESTION_LIKE_TYPES.contains(type)) {
      return null;
    }
    return type + "|" + node.startLine() + "|" + node.endLine();
  }

  private static boolean isUnscored(BlockNodeDTO node) {
    return node.fullScore() == null || node.fullScore() <= 0;
  }

  private static boolean coveredByQuestion(BlockNodeDTO node, List<BlockNodeDTO> questions) {
    return questions.stream().anyMatch(question ->
        question.startLine() <= node.startLine() && question.endLine() >= node.endLine());
  }

  private static String normalizeType(String type) {
    return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
  }
}
