package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PaperPageEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把逐页转写合并成"整卷文本"，同时建立题号 → 页码的映射。
 * <p>
 * 合并阶段只做三件事：清洗页眉页脚、处理续页重复题号、插入页标记。
 * 切题仍然按整卷文本走，所以跨页题干天然连续。
 * </p>
 */
@Component
public class PageTextMerger {

  public static final String PAGE_MARKER_PREFIX = "<<<PAGE:";
  public static final String PAGE_MARKER_SUFFIX = ">>>";

  /** 行首题号：1. / 1． / 1、 / 1) / 1） */
  private static final Pattern QUESTION_NO_PATTERN =
      Pattern.compile("^\\s*(\\d{1,3})\\s*[.．、)）]\\s*");

  private static final List<Pattern> HEADER_FOOTER_PATTERNS = List.of(
      // 允许前缀：「六年级语文试卷　第 2 页（共 4 页）」
      Pattern.compile("^.*第\\s*\\d+\\s*页\\s*[（(]?\\s*共\\s*\\d+\\s*页\\s*[）)]?\\s*$"),
      Pattern.compile("^\\s*第?\\s*\\d+\\s*页\\s*$"),
      Pattern.compile("^\\s*共\\s*\\d+\\s*页.*$"),
      Pattern.compile("^\\s*\\d{1,4}\\s*$"),
      Pattern.compile("^\\s*(学校|班级|姓名|学号|年级)\\s*[:：].{0,30}$"),
      Pattern.compile("^\\s*(https?://|www\\.).*$", Pattern.CASE_INSENSITIVE),
      Pattern.compile("^\\s*\\d{4}\\s*[-年/]\\s*\\d{1,2}\\s*[-月/]\\s*\\d{1,2}\\s*日?\\s*$")
  );

  public record MergeResult(
      String text,
      Map<Integer, int[]> questionPages,
      List<Integer> questionNos,
      List<String> warnings,
      List<int[]> pageRanges
  ) {
    public int[] pagesOf(int questionNo) {
      return questionPages.get(questionNo);
    }
  }

  /** 文本里是否出现行首题号（用于判断短页是不是题目页）。 */
  public static boolean hasLeadingQuestionNo(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    for (String line : text.split("\n")) {
      if (QUESTION_NO_PATTERN.matcher(line).find()) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param pages 按 pageNo 升序的页列表（只应包含 READY 页）
   */
  public MergeResult merge(List<PaperPageEntity> pages) {
    List<String> warnings = new ArrayList<>();
    StringBuilder merged = new StringBuilder();
    Map<Integer, Integer> startPages = new TreeMap<>();
    List<int[]> pageRanges = new ArrayList<>();
    int lastQuestionNo = -1;
    int lastPageNo = 1;

    for (PaperPageEntity page : pages) {
      if (page.getTextContent() == null || page.getTextContent().isBlank()) {
        continue;
      }
      lastPageNo = page.getPageNo();
      List<String> lines = cleanLines(page.getTextContent());
      if (lines.isEmpty()) {
        continue;
      }

      int firstIndex = firstNonBlankIndex(lines);
      if (firstIndex >= 0) {
        Matcher headMatcher = QUESTION_NO_PATTERN.matcher(lines.get(firstIndex));
        if (headMatcher.find() && lastQuestionNo > 0
            && Integer.parseInt(headMatcher.group(1)) == lastQuestionNo) {
          String remainder = lines.get(firstIndex).substring(headMatcher.end());
          lines.set(firstIndex, remainder);
          warnings.add("第 " + page.getPageNo() + " 页顶部重复题号 " + lastQuestionNo + "，已按续页合并");
        }
      }

      int pageStart = merged.length();
      for (String line : lines) {
        merged.append(line).append('\n');
      }
      pageRanges.add(new int[] {page.getPageNo(), pageStart, merged.length()});

      for (String line : lines) {
        Matcher matcher = QUESTION_NO_PATTERN.matcher(line);
        if (matcher.find()) {
          int questionNo = Integer.parseInt(matcher.group(1));
          startPages.putIfAbsent(questionNo, page.getPageNo());
          lastQuestionNo = questionNo;
        }
      }
    }

    Map<Integer, int[]> questionPages = buildRanges(startPages, lastPageNo);
    return new MergeResult(merged.toString().trim(), questionPages,
        new ArrayList<>(questionPages.keySet()), warnings, pageRanges);
  }

  /**
   * 纯拼接：不做页眉清洗、不做题号重排，供"结构模型自己理解层级"的新链路使用。
   */
  public MergeResult mergePlain(List<PaperPageEntity> pages) {
    StringBuilder merged = new StringBuilder();
    List<int[]> pageRanges = new ArrayList<>();
    for (PaperPageEntity page : pages) {
      if (page.getTextContent() == null || page.getTextContent().isBlank()) {
        continue;
      }
      int start = merged.length();
      merged.append(page.getTextContent().strip()).append('\n');
      pageRanges.add(new int[] {page.getPageNo(), start, merged.length()});
    }
    return new MergeResult(merged.toString().trim(), Map.of(), List.of(), List.of(), pageRanges);
  }

  /**
   * 按题干文本定位页码。
   * <p>
   * 不能只依赖"题号 → 页码"：很多试卷每个大题内部重新从 1 开始编号，
   * 切题模型又会把全卷重新编号成 1..N，两边对不上。
   * 这里按「题干在合并文本中的位置」定位，且游标只前进，保证题目顺序与页码单调。
   * </p>
   */
  public static final class PageLocator {

    private final String strippedText;
    private final int[] originalOffsets;
    private final List<int[]> pageRanges;
    private final int lastPageNo;
    private int cursor;

    public PageLocator(String text, List<int[]> pageRanges) {
      this.pageRanges = pageRanges == null ? List.of() : pageRanges;
      StringBuilder stripped = new StringBuilder();
      List<Integer> offsets = new ArrayList<>();
      String source = text == null ? "" : text;
      for (int i = 0; i < source.length(); i++) {
        char ch = source.charAt(i);
        if (Character.isWhitespace(ch)) {
          continue;
        }
        stripped.append(ch);
        offsets.add(i);
      }
      this.strippedText = stripped.toString();
      this.originalOffsets = offsets.stream().mapToInt(Integer::intValue).toArray();
      this.lastPageNo = this.pageRanges.isEmpty()
          ? 1 : this.pageRanges.get(this.pageRanges.size() - 1)[0];
    }

    public int lastPageNo() {
      return lastPageNo;
    }

    /**
     * @return {起始页, 结束页}；定位不到返回 null
     */
    public int[] locate(String questionText) {
      if (questionText == null || questionText.isBlank() || pageRanges.isEmpty()) {
        return null;
      }
      String cleaned = QUESTION_NO_PATTERN.matcher(questionText).replaceFirst("")
          .replaceAll("\\s+", "");
      if (cleaned.isEmpty()) {
        return null;
      }
      for (String anchor : buildAnchors(cleaned)) {
        int index = strippedText.indexOf(anchor, cursor);
        if (index >= 0) {
          cursor = index + anchor.length();
          int pageNo = pageOf(originalOffsets[index]);
          return pageNo > 0 ? new int[] {pageNo, pageNo} : null;
        }
      }
      return null;
    }

    private List<String> buildAnchors(String cleaned) {
      List<String> anchors = new ArrayList<>();
      int maxPrefix = Math.min(20, cleaned.length());
      for (int len = maxPrefix; len >= 5; len--) {
        anchors.add(cleaned.substring(0, len));
      }
      String[] tokens = cleaned.split("[，。、；：？！,.;:?!（）()“”\"《》<>\\[\\]【】…—]+");
      java.util.Arrays.sort(tokens, (a, b) -> b.length() - a.length());
      for (String token : tokens) {
        if (token.length() >= 3) {
          anchors.add(token);
        }
      }
      return anchors;
    }

    private int pageOf(int originalOffset) {
      for (int[] range : pageRanges) {
        if (originalOffset >= range[1] && originalOffset < range[2]) {
          return range[0];
        }
      }
      return -1;
    }
  }

  /**
   * 题号区间：起点是首次出现的页，终点是"下一题首次出现的页"；
   * 最后一题收口在整卷末页。
   */
  private Map<Integer, int[]> buildRanges(Map<Integer, Integer> startPages, int lastPageNo) {
    Map<Integer, int[]> ranges = new LinkedHashMap<>();
    List<Integer> questionNos = new ArrayList<>(startPages.keySet());
    for (int i = 0; i < questionNos.size(); i++) {
      int questionNo = questionNos.get(i);
      int from = startPages.get(questionNo);
      int to = i + 1 < questionNos.size()
          ? Math.max(from, startPages.get(questionNos.get(i + 1)))
          : Math.max(from, lastPageNo);
      ranges.put(questionNo, new int[] {from, to});
    }
    return ranges;
  }

  List<String> cleanLines(String text) {
    List<String> lines = new ArrayList<>();
    for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
      String line = raw.stripTrailing();
      if (line.isBlank()) {
        continue;
      }
      if (isHeaderOrFooter(line)) {
        continue;
      }
      lines.add(line);
    }
    return lines;
  }

  private boolean isHeaderOrFooter(String line) {
    for (Pattern pattern : HEADER_FOOTER_PATTERNS) {
      if (pattern.matcher(line).matches()) {
        return true;
      }
    }
    return false;
  }

  private int firstNonBlankIndex(List<String> lines) {
    for (int i = 0; i < lines.size(); i++) {
      if (!lines.get(i).isBlank()) {
        return i;
      }
    }
    return -1;
  }
}
