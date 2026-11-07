package interview.guide.modules.resume.service;

import java.util.Arrays;

/**
 * 一个页面资源在全卷连续行号中的确定性锚点。
 */
public record PaperAssetBinding(
    String assetKey,
    int pageNo,
    int anchorLine,
    String kind,
    String role,
    double[] rect
) {

  public PaperAssetBinding {
    rect = rect == null ? null : Arrays.copyOf(rect, rect.length);
  }
}
