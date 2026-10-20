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
    Integer height
) {
  public static DocAsset figure(String key, byte[] bytes, String anchorText, int width, int height) {
    return new DocAsset(key, AssetKind.FIGURE, AssetRole.STEM, bytes, anchorText,
        null, null, null, null, width, height);
  }
}
