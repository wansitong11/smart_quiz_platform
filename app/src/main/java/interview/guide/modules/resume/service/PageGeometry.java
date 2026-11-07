package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognition;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 试卷识别中的坐标换算与区域关系纯函数。
 */
public final class PageGeometry {

  private PageGeometry() {
  }

  /**
   * 把模型返回的归一化资源 bbox 转成页内像素区域；无效或越界时返回 null。
   */
  public static Rectangle pixelRect(PageRecognition.PageAsset asset,
                                    int pageWidth,
                                    int pageHeight) {
    return resolvePixelRect(asset, pageWidth, pageHeight).rect();
  }

  /**
   * 与 {@link #pixelRect(PageRecognition.PageAsset, int, int)} 使用同一套换算规则，
   * 同时返回失败原因，便于识别链路定位被丢弃的模型 bbox。
   */
  public static PixelRectResolution resolvePixelRect(
      PageRecognition.PageAsset asset,
      int pageWidth,
      int pageHeight
  ) {
    double[] bbox = asset.bbox();
    if (bbox == null || bbox.length < 4) {
      return new PixelRectResolution(null,
          "bbox-null-or-too-short bbox=" + formatBbox(bbox)
              + " page=" + pageWidth + "x" + pageHeight);
    }
    double[] normalizedBbox = normalizeBboxIfPixels(bbox, pageWidth, pageHeight);
    double normalizedX = Math.max(0, Math.min(1, normalizedBbox[0]));
    double normalizedY = Math.max(0, Math.min(1, normalizedBbox[1]));
    int x = (int) Math.round(normalizedX * pageWidth);
    int y = (int) Math.round(normalizedY * pageHeight);
    int width = (int) Math.round(Math.min(1 - normalizedX, normalizedBbox[2]) * pageWidth);
    int height = (int) Math.round(Math.min(1 - normalizedY, normalizedBbox[3]) * pageHeight);
    if ("OPTION_ROW".equalsIgnoreCase(asset.kind())) {
      double ratio = asset.layout() != null && asset.layout().toLowerCase().contains("2x")
          ? 1.1 : 0.55;
      height = Math.min(height, Math.max(10, (int) Math.round(width * ratio)));
    }
    if (width < 10 || height < 10 || x + width > pageWidth || y + height > pageHeight) {
      return new PixelRectResolution(null,
          "invalid-pixel-rect x=" + x + " y=" + y + " width=" + width + " height=" + height
              + " page=" + pageWidth + "x" + pageHeight + " bbox=" + formatBbox(bbox)
              + " kind=" + asset.kind() + " layout=" + asset.layout());
    }
    return new PixelRectResolution(new Rectangle(x, y, width, height), null);
  }

  public static Rectangle pixelRect(PageRecognition.PageLine line, BufferedImage page) {
    double[] bbox = normalizeBboxIfPixels(line.bbox(), page.getWidth(), page.getHeight());
    int x = (int) Math.round(Math.max(0, Math.min(1, bbox[0])) * page.getWidth());
    int y = (int) Math.round(Math.max(0, Math.min(1, bbox[1])) * page.getHeight());
    int width = (int) Math.round(Math.min(1 - bbox[0], bbox[2]) * page.getWidth());
    int height = (int) Math.round(Math.min(1 - bbox[1], bbox[3]) * page.getHeight());
    return new Rectangle(x, y, Math.max(1, width), Math.max(1, height));
  }

  public static Rectangle union(List<Rectangle> rectangles) {
    Rectangle result = new Rectangle(rectangles.getFirst());
    for (int i = 1; i < rectangles.size(); i++) {
      result = result.union(rectangles.get(i));
    }
    return result;
  }

  public static boolean inSameColumn(Rectangle candidate, Rectangle column) {
    int centerX = candidate.x + candidate.width / 2;
    int padding = Math.max(12, column.width / 10);
    return centerX >= column.x - padding
        && centerX <= column.x + column.width + padding;
  }

  /**
   * 表格资源的距离权重偏向顶部锚点，避免模型 bbox 向下扩张时匹配到错误表格。
   */
  public static double anchorDistance(Rectangle seed, Rectangle region) {
    double dx = seed.getCenterX() - region.getCenterX();
    double dy = seed.y - region.y;
    return dx * dx + dy * dy * 2;
  }

  public static double[] normalizedRect(Rectangle rect, int pageWidth, int pageHeight) {
    return new double[] {
        (double) rect.x / pageWidth,
        (double) rect.y / pageHeight,
        (double) rect.width / pageWidth,
        (double) rect.height / pageHeight
    };
  }

  /**
   * 兼容少数模型直接返回像素坐标的情况。
   *
   * <p>归一化坐标偶尔会因模型估算出现 1.0 左右的轻微越界（例如 1.067），
   * 因此不能把“大于 1”直接当成像素坐标；这里只在明显超出归一化范围，
   * 且仍然落在页面尺寸内时才按像素值换算，避免误伤正常的 bbox。</p>
   */
  static double[] normalizeBboxIfPixels(double[] bbox, int pageWidth, int pageHeight) {
    if (bbox == null || bbox.length < 4 || pageWidth <= 0 || pageHeight <= 0) {
      return bbox;
    }
    double max = Math.max(
        Math.max(Math.abs(bbox[0]), Math.abs(bbox[1])),
        Math.max(Math.abs(bbox[2]), Math.abs(bbox[3])));
    if (max <= 2.0 || max > Math.max(pageWidth, pageHeight)) {
      return bbox;
    }
    return new double[] {
        bbox[0] / pageWidth,
        bbox[1] / pageHeight,
        bbox[2] / pageWidth,
        bbox[3] / pageHeight
    };
  }

  private static String formatBbox(double[] bbox) {
    if (bbox == null) {
      return "null";
    }
    StringBuilder value = new StringBuilder("[");
    for (int i = 0; i < bbox.length; i++) {
      if (i > 0) {
        value.append(',');
      }
      value.append(bbox[i]);
    }
    return value.append(']').toString();
  }

  public record PixelRectResolution(Rectangle rect, String failureReason) {

    public boolean resolved() {
      return rect != null;
    }
  }
}
