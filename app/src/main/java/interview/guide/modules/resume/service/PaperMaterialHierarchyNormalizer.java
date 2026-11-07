package interview.guide.modules.resume.service;

import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 合并被分页或分块拆开的阅读材料，并识别题目旁重复展示的段落摘录。
 *
 * <p>托福等阅读卷常先给出完整文章，随后在每道题前再次展示对应段落。模型会把这些
 * 段落复制品都标成 MATERIAL；若直接按“最近材料”挂题，完整文章会变成没有题目的
 * 孤立节点，题目则散落在多个段落下面。这里在进入树组装前先做确定性归一化：</p>
 * <ul>
 *   <li>相邻且明显是续写片段的 MATERIAL 合并为一个完整材料；</li>
 *   <li>正文已被前一个完整材料包含的 MATERIAL 标记为 EXCERPT，不切换当前材料。</li>
 * </ul>
 */
final class PaperMaterialHierarchyNormalizer {

  static final String TYPE_EXCERPT = "EXCERPT";

  private static final Pattern PARAGRAPH_LABEL = Pattern.compile(
      "(?im)^\\s*(?:paragraph|段落)\\s*\\d+\\s*$");
  private static final Pattern PARAGRAPH_TITLE = Pattern.compile(
      "(?i)^\\s*(?:paragraph|段落)\\s*\\d+\\s*$");
  private static final Pattern HYPHENATED_LINE_BREAK = Pattern.compile(
      "(?<=\\p{L})-\\s*\\R\\s*(?=\\p{L})");
  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
  private static final int MIN_EXCERPT_CHARS = 80;
  private static final int MIN_SHINGLE_LENGTH = 6;
  private static final int MAX_SHINGLE_LENGTH = 12;
  private static final double MIN_CONTAINMENT_RATIO = 0.88;
  private static final Set<String> GENERIC_CONTINUATION_TITLES = Set.of(
      "", "材料", "阅读材料", "阅读材料片段", "续", "continued", "continuation"
  );

  private PaperMaterialHierarchyNormalizer() {
  }

  static List<BlockNodeDTO> normalize(List<BlockNodeDTO> nodes, List<String> lines) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    List<BlockNodeDTO> normalized = new ArrayList<>(nodes.size());
    for (BlockNodeDTO node : nodes) {
      if (!isMaterial(node)) {
        normalized.add(node);
        continue;
      }

      String text = textOf(node, lines);
      String comparableText = normalizeText(text);
      if (isRepeatedExcerpt(comparableText, normalized, lines)) {
        normalized.add(withType(node, TYPE_EXCERPT));
        continue;
      }

      int previousIndex = normalized.size() - 1;
      if (previousIndex >= 0) {
        BlockNodeDTO previous = normalized.get(previousIndex);
        if (isContinuation(previous, node, lines)) {
          normalized.set(previousIndex, merge(previous, node, lines));
          continue;
        }
      }
      normalized.add(node);
    }
    return List.copyOf(normalized);
  }

  private static boolean isRepeatedExcerpt(
      String candidateText,
      List<BlockNodeDTO> previousNodes,
      List<String> lines
  ) {
    if (candidateText.length() < MIN_EXCERPT_CHARS) {
      return false;
    }
    for (int i = previousNodes.size() - 1; i >= 0; i--) {
      BlockNodeDTO previous = previousNodes.get(i);
      if (!isMaterial(previous)) {
        continue;
      }
      String parentText = normalizeText(textOf(previous, lines));
      if (candidateText.length() < parentText.length() && isMostlyContained(candidateText, parentText)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 不依赖“Paragraph 1 / 第 1 段”等固定标签：用字符 n-gram 覆盖率识别
   * “正文基本相同、只多了少量题注或 OCR 差异”的段落摘录。
   */
  private static boolean isMostlyContained(String candidate, String parent) {
    if (parent.contains(candidate)) {
      return true;
    }
    String compactCandidate = candidate.replace(" ", "");
    String compactParent = parent.replace(" ", "");
    if (compactCandidate.length() < MIN_EXCERPT_CHARS
        || compactCandidate.length() >= compactParent.length()) {
      return false;
    }
    int shingleLength = Math.min(MAX_SHINGLE_LENGTH,
        Math.max(MIN_SHINGLE_LENGTH, compactCandidate.length() / 24));
    int total = compactCandidate.length() - shingleLength + 1;
    if (total <= 0) {
      return false;
    }
    int matched = 0;
    for (int i = 0; i < total; i++) {
      if (compactParent.contains(compactCandidate.substring(i, i + shingleLength))) {
        matched++;
      }
    }
    return (double) matched / total >= MIN_CONTAINMENT_RATIO;
  }

  private static boolean isContinuation(
      BlockNodeDTO previous,
      BlockNodeDTO current,
      List<String> lines
  ) {
    if (!isMaterial(previous) || current.startLine() != previous.endLine() + 1) {
      return false;
    }
    if (isParagraphTitle(current.title())) {
      return false;
    }
    String previousText = textOf(previous, lines);
    return isGenericContinuationTitle(current.title())
        || !endsWithSentencePunctuation(previousText);
  }

  private static BlockNodeDTO merge(
      BlockNodeDTO previous,
      BlockNodeDTO current,
      List<String> lines
  ) {
    String content = textOf(previous, lines) + "\n" + textOf(current, lines);
    List<String> assetRefs = new ArrayList<>();
    if (previous.assetRefs() != null) {
      assetRefs.addAll(previous.assetRefs());
    }
    if (current.assetRefs() != null) {
      assetRefs.addAll(current.assetRefs());
    }
    return new BlockNodeDTO(
        previous.type(),
        previous.startLine(),
        current.endLine(),
        previous.title(),
        previous.questionType(),
        previous.fullScore(),
        previous.options(),
        previous.referenceAnswer(),
        previous.keyPoints(),
        previous.scoringRubric(),
        previous.analysis(),
        previous.category(),
        List.copyOf(new LinkedHashSet<>(assetRefs)),
        previous.optionType()
    );
  }

  /**
   * 测试和诊断时可直接比较归一化后的材料文本。
   */
  static String normalizeText(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String normalized = text
        .replaceAll("(?s)\\{\\{(.*?)}}", "$1")
        .replaceAll("(?s)\\[\\[[uw]:(.*?)]]", "$1");
    normalized = PARAGRAPH_LABEL.matcher(normalized).replaceAll(" ");
    normalized = HYPHENATED_LINE_BREAK.matcher(normalized).replaceAll("");
    normalized = NON_WORD.matcher(normalized.toLowerCase(Locale.ROOT)).replaceAll(" ");
    return normalized.strip().replaceAll("\\s+", " ");
  }

  private static String textOf(BlockNodeDTO node, List<String> lines) {
    if (lines == null || lines.isEmpty() || node.startLine() < 1) {
      return "";
    }
    int start = Math.min(node.startLine(), lines.size());
    int end = Math.min(Math.max(start, node.endLine()), lines.size());
    return String.join("\n", lines.subList(start - 1, end));
  }

  private static boolean isGenericContinuationTitle(String title) {
    if (title == null) {
      return true;
    }
    String normalized = title.strip().toLowerCase(Locale.ROOT);
    return GENERIC_CONTINUATION_TITLES.contains(normalized)
        || normalized.contains("材料片段");
  }

  private static boolean isParagraphTitle(String title) {
    return title != null && PARAGRAPH_TITLE.matcher(title).matches();
  }

  private static boolean endsWithSentencePunctuation(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    char last = text.strip().charAt(text.strip().length() - 1);
    return ".?!。！？；;:：)]}）】”’\"".indexOf(last) >= 0;
  }

  private static boolean isMaterial(BlockNodeDTO node) {
    return node != null && "MATERIAL".equalsIgnoreCase(node.type());
  }

  private static BlockNodeDTO withType(BlockNodeDTO node, String type) {
    return new BlockNodeDTO(
        type,
        node.startLine(),
        node.endLine(),
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
