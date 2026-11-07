package interview.guide.modules.resume.model;

import java.util.Arrays;

/**
 * 页面资源的稳定几何信息，随 {@link PageRecognitionGeometrySnapshot} 持久化。
 */
public record PageAssetGeometry(
    String assetKey,
    String kind,
    String role,
    String anchorText,
    double[] rect,
    String layout,
    Integer optionCount
) {

  public PageAssetGeometry {
    rect = rect == null ? null : Arrays.copyOf(rect, rect.length);
  }
}
