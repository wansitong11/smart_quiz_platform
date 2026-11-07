package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognition.PageAsset;
import interview.guide.modules.resume.model.PageRecognitionDiagnostic;
import interview.guide.modules.resume.model.StemFigureVerdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题干图声明与最终图片资源的一致性纯函数。
 *
 * <p>识别模型在 {@code stemFigureAnchors} 里声明哪些题有独立题干图，并要求每个声明
 * 对应一个 {@code FIGURE/STEM} 资源。这里把「声明 vs 最终资源」的匹配做成逐项判定，
 * 失败时能说出到底哪个锚点缺失或无法唯一对齐，而不是只抛一句泛化文案。</p>
 */
public final class PaperStemFigureConsistency {

  private static final int MIN_CONTAINS_LENGTH = 4;
  private static final int DIAGNOSTIC_ANCHOR_CHARS = 40;
  private static final int FAILURE_ANCHOR_CHARS = 24;

  private PaperStemFigureConsistency() {
  }

  /**
   * 逐项判定声明锚点是否被最终 FIGURE/STEM 资源覆盖。
   *
   * @return 按声明原文（trim 后）为 key 的结论映射，保留声明顺序
   */
  public static Map<String, StemFigureVerdict> check(
      List<String> stemFigureAnchors,
      List<PageAsset> assets
  ) {
    Map<String, StemFigureVerdict> verdicts = new LinkedHashMap<>();
    if (stemFigureAnchors == null) {
      return verdicts;
    }
    Set<String> actualAnchors = canonicalStemAnchors(assets);
    for (String anchor : stemFigureAnchors) {
      String key = anchor == null ? "" : anchor.strip();
      if (key.isBlank() || verdicts.containsKey(key)) {
        continue;
      }
      String declared = canonical(key);
      long matches = actualAnchors.stream()
          .filter(actual -> anchorsMatch(declared, actual))
          .count();
      verdicts.put(key, matches == 0
          ? StemFigureVerdict.MISSING
          : matches == 1 ? StemFigureVerdict.MATCHED : StemFigureVerdict.AMBIGUOUS);
    }
    return verdicts;
  }

  /**
   * 是否存在缺失或无法唯一对齐的题干图；供页面校验与识别重试复用。
   */
  public static boolean hasMissing(
      List<String> stemFigureAnchors,
      List<PageAsset> assets
  ) {
    return check(stemFigureAnchors, assets).values().stream()
        .anyMatch(verdict -> verdict != StemFigureVerdict.MATCHED);
  }

  /**
   * 生成可读失败文案；全部 MATCHED 时返回 null。
   */
  public static String failureMessage(int pageNo, Map<String, StemFigureVerdict> verdicts) {
    if (verdicts == null || verdicts.isEmpty()) {
      return null;
    }
    List<String> missing = new ArrayList<>();
    List<String> ambiguous = new ArrayList<>();
    verdicts.forEach((anchor, verdict) -> {
      if (verdict == StemFigureVerdict.MISSING) {
        missing.add(truncate(anchor, FAILURE_ANCHOR_CHARS));
      } else if (verdict == StemFigureVerdict.AMBIGUOUS) {
        ambiguous.add(truncate(anchor, FAILURE_ANCHOR_CHARS));
      }
    });
    if (missing.isEmpty() && ambiguous.isEmpty()) {
      return null;
    }
    StringBuilder message = new StringBuilder("第 ").append(pageNo).append(" 页");
    if (!missing.isEmpty()) {
      message.append("题干图缺失：").append(String.join("、", missing));
    }
    if (!ambiguous.isEmpty()) {
      if (!missing.isEmpty()) {
        message.append("；");
      }
      message.append("题干图锚点无法唯一对齐：").append(String.join("、", ambiguous));
    }
    return message.append("，请重试该页或人工补图").toString();
  }

  /**
   * 构造可落库的诊断快照，锚点只保留摘要。
   */
  public static PageRecognitionDiagnostic diagnostic(
      String failureClass,
      int pageNo,
      List<String> stemFigureAnchors,
      List<PageAsset> assets,
      Map<String, StemFigureVerdict> verdicts
  ) {
    Map<String, StemFigureVerdict> boundedVerdicts = new LinkedHashMap<>();
    if (verdicts != null) {
      verdicts.forEach((anchor, verdict) ->
          boundedVerdicts.put(truncate(anchor, DIAGNOSTIC_ANCHOR_CHARS), verdict));
    }
    return new PageRecognitionDiagnostic(
        failureClass,
        pageNo,
        summarizeAnchors(stemFigureAnchors),
        summarizeAnchors(resolvedStemAnchorTexts(assets)),
        boundedVerdicts
    );
  }

  private static Set<String> canonicalStemAnchors(List<PageAsset> assets) {
    if (assets == null) {
      return Set.of();
    }
    return assets.stream()
        .filter(asset -> asset != null
            && "FIGURE".equalsIgnoreCase(asset.kind())
            && "STEM".equalsIgnoreCase(asset.role()))
        .map(PageAsset::anchorText)
        .map(PaperStemFigureConsistency::canonical)
        .filter(anchor -> !anchor.isBlank())
        .collect(Collectors.toSet());
  }

  private static List<String> resolvedStemAnchorTexts(List<PageAsset> assets) {
    if (assets == null) {
      return List.of();
    }
    return assets.stream()
        .filter(asset -> asset != null
            && "FIGURE".equalsIgnoreCase(asset.kind())
            && "STEM".equalsIgnoreCase(asset.role()))
        .map(PageAsset::anchorText)
        .filter(anchor -> anchor != null && !anchor.isBlank())
        .toList();
  }

  private static List<String> summarizeAnchors(List<String> anchors) {
    if (anchors == null) {
      return List.of();
    }
    return anchors.stream()
        .filter(anchor -> anchor != null && !anchor.isBlank())
        .map(String::strip)
        .map(anchor -> truncate(anchor, DIAGNOSTIC_ANCHOR_CHARS))
        .toList();
  }

  static String canonical(String value) {
    if (value == null) {
      return "";
    }
    // 空白、标点、货币/数学符号统一剔除；中文题号与文字保留，另在 anchorsMatch 里处理题号前缀。
    return value.replaceAll("[\\s\\p{P}\\p{S}]+", "");
  }

  static boolean anchorsMatch(String declared, String actual) {
    if (declared.equals(actual)) {
      return true;
    }
    if (declared.length() >= MIN_CONTAINS_LENGTH && actual.contains(declared)) {
      return true;
    }
    if (actual.length() >= MIN_CONTAINS_LENGTH && declared.contains(actual)) {
      return true;
    }
    // 去掉题号前缀（“第8题 / 8. / 8、”）再比一次，容忍模型对题号的写法差异。
    String declaredNoNumber = stripLeadingNumber(declared);
    String actualNoNumber = stripLeadingNumber(actual);
    boolean numberStripped = !declaredNoNumber.equals(declared)
        || !actualNoNumber.equals(actual);
    if (numberStripped && declaredNoNumber.length() >= MIN_CONTAINS_LENGTH
        && actualNoNumber.contains(declaredNoNumber)) {
      return true;
    }
    if (numberStripped && actualNoNumber.length() >= MIN_CONTAINS_LENGTH
        && declaredNoNumber.contains(actualNoNumber)) {
      return true;
    }
    return false;
  }

  private static String stripLeadingNumber(String value) {
    return value.replaceFirst("^第?\\d+[题、.．]?", "");
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength) + "…";
  }
}
