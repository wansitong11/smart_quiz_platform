package interview.guide.modules.resume.service;

import interview.guide.modules.resume.document.DocAsset;
import interview.guide.modules.resume.model.PageAssetGeometry;
import interview.guide.modules.resume.model.PageRecognition;
import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.log.PaperFlowLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 为不经过视觉模型的 TEXT 页补齐确定性行几何。
 *
 * <p>PDF 资源保留真实 rect；DOCX 图片没有 bbox，使用解析器给出的页内行号生成
 * 与行带一致的合成 rect。后续资源归属仍由 {@link PaperAssetOwnershipResolver}
 * 统一计算，不在解析器里决定题目父子关系。</p>
 */
public final class TextPageGeometry {

  private static final double MAX_SYNTHETIC_LINE_HEIGHT = 0.04;

  private TextPageGeometry() {
  }

  public static PageRecognitionGeometrySnapshot fromParsedPage(
      int pageNo,
      List<String> pageLines,
      List<DocAsset> assets
  ) {
    return fromParsedPage(pageNo, pageLines, assets, Map.of());
  }

  public static PageRecognitionGeometrySnapshot fromParsedPage(
      int pageNo,
      List<String> pageLines,
      List<DocAsset> assets,
      Map<String, String> keyMapping
  ) {
    List<String> lines = pageLines == null ? List.of() : pageLines;
    List<PageRecognition.PageLine> geometryLines = syntheticLines(lines);
    List<PageAssetGeometry> geometries = new ArrayList<>();
    for (DocAsset asset : assets == null ? List.<DocAsset>of() : assets) {
      if (asset == null || asset.key() == null || asset.key().isBlank()) {
        continue;
      }
      String assetKey = keyMapping.getOrDefault(asset.key(), asset.key());
      geometries.add(new PageAssetGeometry(
          assetKey,
          asset.kind() == null ? null : asset.kind().name(),
          asset.role() == null ? null : asset.role().name(),
          asset.anchorText(),
          rectFor(asset, lines.size()),
          asset.layout(),
          asset.optionCount()
      ));
    }
    return new PageRecognitionGeometrySnapshot(
        PageRecognitionGeometrySnapshot.CURRENT_VERSION,
        pageNo,
        geometryLines,
        geometries
    );
  }

  public static PageRecognitionGeometrySnapshot fromStoredPage(
      int pageNo,
      List<PaperAssetEntity> assets,
      List<String> pageLines
  ) {
    List<String> lines = pageLines == null ? List.of() : pageLines;
    List<PageAssetGeometry> geometries = new ArrayList<>();
    for (PaperAssetEntity asset : assets == null ? List.<PaperAssetEntity>of() : assets) {
      if (asset == null || asset.getParentKey() != null
          || asset.getAssetKey() == null || asset.getAssetKey().isBlank()) {
        continue;
      }
      geometries.add(new PageAssetGeometry(
          asset.getAssetKey(),
          asset.getKind(),
          asset.getRole(),
          asset.getAnchorText(),
          parseRect(asset.getRectJson()),
          asset.getLayout(),
          asset.getOptionCount()
      ));
    }
    return new PageRecognitionGeometrySnapshot(
        PageRecognitionGeometrySnapshot.CURRENT_VERSION,
        pageNo,
        syntheticLines(lines),
        geometries
    );
  }

  private static List<PageRecognition.PageLine> syntheticLines(List<String> lines) {
    if (lines.isEmpty()) {
      return List.of();
    }
    double step = 1.0 / lines.size();
    double height = Math.min(MAX_SYNTHETIC_LINE_HEIGHT, step * 0.8);
    List<PageRecognition.PageLine> result = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      result.add(new PageRecognition.PageLine(
          lines.get(i),
          new double[] {0, i * step, 1, height}
      ));
    }
    return result;
  }

  private static double[] rectFor(DocAsset asset, int lineCount) {
    if (asset.rect() != null && asset.rect().length >= 4) {
      return Arrays.copyOf(asset.rect(), 4);
    }
    return syntheticRect(asset.anchorLineIndex(), lineCount);
  }

  private static double[] syntheticRect(Integer lineIndex, int lineCount) {
    if (lineIndex == null || lineIndex < 0 || lineCount <= 0) {
      return null;
    }
    int bounded = Math.min(lineIndex, lineCount - 1);
    double step = 1.0 / lineCount;
    return new double[] {0, bounded * step, 1, Math.min(MAX_SYNTHETIC_LINE_HEIGHT, step * 0.8)};
  }

  private static double[] parseRect(String rectJson) {
    if (rectJson == null || rectJson.isBlank()) {
      return null;
    }
    try {
      String normalized = rectJson.replace("[", "").replace("]", "");
      String[] parts = normalized.split(",");
      if (parts.length < 4) {
        return null;
      }
      double[] rect = new double[4];
      for (int i = 0; i < 4; i++) {
        rect[i] = Double.parseDouble(parts[i].trim());
      }
      return rect;
    } catch (NumberFormatException e) {
      PaperFlowLog.error("TEXT_PAGE_GEOMETRY_RECT_INVALID rectJson={}", e, rectJson);
      return null;
    }
  }
}
