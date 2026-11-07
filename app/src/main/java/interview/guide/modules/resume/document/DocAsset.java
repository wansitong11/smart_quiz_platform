package interview.guide.modules.resume.document;

import java.util.List;

/**
 * 解析阶段拿到的资源（尚未落 S3）：图片字节 + 锚点 + 可选坐标。
 */
public record DocAsset(
    String key,
    AssetKind kind,
    AssetRole role,
    byte[] bytes,
    String anchorText,
    String layout,
    Integer optionCount,
    List<String> labels,
    double[] rect,
    Integer width,
    Integer height,
    Integer anchorLineIndex
) {
  public DocAsset(
      String key,
      AssetKind kind,
      AssetRole role,
      byte[] bytes,
      String anchorText,
      String layout,
      Integer optionCount,
      List<String> labels,
      double[] rect,
      Integer width,
      Integer height
  ) {
    this(key, kind, role, bytes, anchorText, layout, optionCount, labels, rect,
        width, height, null);
  }

  public static DocAsset figure(String key, byte[] bytes, String anchorText, int width, int height) {
    return new DocAsset(key, AssetKind.FIGURE, AssetRole.STEM, bytes, anchorText,
        null, null, null, null, width, height);
  }

  /**
   * 文本解析器在把资源写入某一行后补上页内行号，切题阶段不必再按相同文本猜锚点。
   */
  public DocAsset withAnchorLineIndex(int lineIndex) {
    return new DocAsset(key, kind, role, bytes, anchorText, layout, optionCount, labels,
        rect, width, height, lineIndex);
  }
}
