package interview.guide.modules.resume.service;

import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 修正结构模型偶发的题目行号重叠。
 * 模型通常能从标题中识别题号，但可能把下一题的起始行误报成上一题选项区的某一行。
 */
final class PaperBlockBoundaryNormalizer {

  private static final Pattern QUESTION_NUMBER = Pattern.compile(
      "^\\s*(?:第\\s*)?[Qq]?\\s*(\\d{1,3})(?=\\s|[.．、:：)）\\[]|$)");
  private static final Set<String> BOUNDARY_TYPES = Set.of(
      "PART", "SECTION", "MATERIAL", "QUESTION", "INSTRUCTION", "ANSWER_KEY");

  private PaperBlockBoundaryNormalizer() {
  }

  static List<BlockNodeDTO> normalize(List<BlockNodeDTO> nodes, List<String> lines) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    List<BlockNodeDTO> aligned = new ArrayList<>(nodes.size());
    for (BlockNodeDTO node : nodes) {
      aligned.add(isQuestion(node.type()) ? alignStartLine(node, lines) : node);
    }
    aligned.sort(Comparator
        .comparingInt(BlockNodeDTO::startLine)
        .thenComparingInt(BlockNodeDTO::endLine));

    List<BlockNodeDTO> normalized = new ArrayList<>(aligned.size());
    for (int i = 0; i < aligned.size(); i++) {
      BlockNodeDTO node = aligned.get(i);
      if (!isQuestion(node.type())) {
        normalized.add(node);
        continue;
      }
      int nextBoundary = lines.size() + 1;
      for (int j = i + 1; j < aligned.size(); j++) {
        BlockNodeDTO next = aligned.get(j);
        if (next.startLine() > node.startLine() && isBoundary(next.type())) {
          nextBoundary = next.startLine();
          break;
        }
      }
      if (node.startLine() < nextBoundary && node.endLine() >= nextBoundary) {
        normalized.add(withLines(node, node.startLine(), nextBoundary - 1));
      } else {
        normalized.add(node);
      }
    }
    return List.copyOf(normalized);
  }

  private static BlockNodeDTO alignStartLine(BlockNodeDTO node, List<String> lines) {
    Integer questionNumber = questionNumber(node.title());
    if (questionNumber == null || lines.isEmpty()) {
      return node;
    }
    int originalStart = Math.max(1, Math.min(lines.size(), node.startLine()));
    int maxDistance = 6;
    for (int distance = 0; distance <= maxDistance; distance++) {
      int before = originalStart - distance;
      int after = originalStart + distance;
      if (distance == 0 && matchesQuestionNumber(lines.get(before - 1), questionNumber)) {
        return node;
      }
      if (before >= 1 && matchesQuestionNumber(lines.get(before - 1), questionNumber)) {
        return withLines(node, before, Math.max(before, node.endLine()));
      }
      if (after <= lines.size() && matchesQuestionNumber(lines.get(after - 1), questionNumber)) {
        return withLines(node, after, Math.max(after, node.endLine()));
      }
    }
    return node;
  }

  private static Integer questionNumber(String title) {
    if (title == null) {
      return null;
    }
    Matcher matcher = QUESTION_NUMBER.matcher(title);
    if (!matcher.find()) {
      return null;
    }
    try {
      return Integer.parseInt(matcher.group(1));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static boolean matchesQuestionNumber(String line, int questionNumber) {
    Matcher matcher = QUESTION_NUMBER.matcher(line);
    if (!matcher.find()) {
      return false;
    }
    try {
      return Integer.parseInt(matcher.group(1)) == questionNumber;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static boolean isQuestion(String type) {
    return "QUESTION".equalsIgnoreCase(type);
  }

  private static boolean isBoundary(String type) {
    return type != null && BOUNDARY_TYPES.contains(type.toUpperCase());
  }

  private static BlockNodeDTO withLines(BlockNodeDTO node, int startLine, int endLine) {
    return new BlockNodeDTO(
        node.type(),
        startLine,
        endLine,
        node.title(),
        node.questionType(),
        node.fullScore(),
        node.options(),
        node.referenceAnswer(),
        node.keyPoints(),
        node.scoringRubric(),
        node.analysis(),
        node.category(),
        node.assetRefs(),
        node.optionType()
    );
  }
}
