package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PaperAssetEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将识别阶段生成的题干图确定性挂接到题目。
 *
 * <p>结构化模型会返回 assetRef，但可能漏掉题干旁的小图。这里只在题干明确
 * 引用图形、且资源的 anchorLine 位于当前题目行范围内时补挂，避免按“同页”
 * 粗暴兜底造成跨题串图。</p>
 */
@Component
public class PaperQuestionStemAssetMatcher {

  private static final Pattern STEM_VISUAL_REFERENCE = Pattern.compile(
      "如图|图中|下图|上图|图\\s*[（(]\\s*\\d+");

  public List<String> resolve(
      String questionText,
      List<String> referencedAssetIds,
      List<PaperAssetEntity> assets,
      int startLine,
      int endLine
  ) {
    if (questionText == null
        || !STEM_VISUAL_REFERENCE.matcher(questionText).find()
        || assets == null || assets.isEmpty()) {
      return List.of();
    }
    Set<String> referenced = referencedAssetIds == null
        ? Set.of() : Set.copyOf(referencedAssetIds);
    Set<Integer> referencedAnchorLines = assets.stream()
        .filter(asset -> asset != null && referenced.contains(asset.getAssetKey()))
        .filter(asset -> "FIGURE".equals(asset.getKind())
            || asset.getKind() != null && asset.getKind().startsWith("OPTION_"))
        .map(PaperAssetEntity::getAnchorLine)
        .filter(anchor -> anchor != null)
        .collect(java.util.stream.Collectors.toSet());
    int nearestAnchor = nearestStemAnchor(assets, startLine, endLine);
    return assets.stream()
        .filter(asset -> asset != null && asset.getAssetKey() != null)
        .filter(asset -> !referenced.contains(asset.getAssetKey()))
        .filter(asset -> "FIGURE".equals(asset.getKind()))
        .filter(asset -> "STEM".equals(asset.getRole()))
        .filter(asset -> referencedAnchorLines.isEmpty()
            ? asset.getAnchorLine() != null && nearestAnchor != Integer.MAX_VALUE
                && Math.abs(asset.getAnchorLine() - startLine)
                == Math.abs(nearestAnchor - startLine)
            : referencedAnchorLines.contains(asset.getAnchorLine()))
        .filter(asset -> asset.getAnchorLine() != null
            && asset.getAnchorLine() >= startLine
            && asset.getAnchorLine() <= endLine)
        .sorted(java.util.Comparator
            .comparing(PaperAssetEntity::getAnchorLine)
            .thenComparing(PaperAssetEntity::getAssetKey))
        .map(PaperAssetEntity::getAssetKey)
        .distinct()
        .toList();
  }

  private int nearestStemAnchor(
      List<PaperAssetEntity> assets,
      int startLine,
      int endLine
  ) {
    return assets.stream()
        .filter(asset -> asset != null && "FIGURE".equals(asset.getKind()))
        .filter(asset -> "STEM".equals(asset.getRole()))
        .map(PaperAssetEntity::getAnchorLine)
        .filter(anchor -> anchor != null && anchor >= startLine && anchor <= endLine)
        .min(java.util.Comparator.comparingInt(anchor -> Math.abs(anchor - startLine)))
        .orElse(Integer.MAX_VALUE);
  }
}
