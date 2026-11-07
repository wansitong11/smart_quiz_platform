package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognition.PageAsset;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 识别结果的完整性校验。
 *
 * <p>视觉模型偶尔只返回页面第一行，但仍是合法 JSON；仅靠字符数 20 的判断会漏掉这种
 * 大面积漏读。这里用页面墨迹行带数量与识别字符数做交叉判断，异常时触发重新识别。</p>
 */
@Component
public class PageRecognitionValidator {

  private static final int MIN_COMPLETE_CHARS = 100;
  private static final int MIN_DENSE_TEXT_BANDS = 8;
  private static final Pattern VISUAL_REFERENCE = Pattern.compile(
      "如图|图中|下图|上图|图\\s*[（(]\\s*\\d+\\s*[）)]|展开图|示意图|统计图|数轴|几何图");
  private static final Pattern STEM_REFERENCE = Pattern.compile(
      "如图|图中|下图|上图|图\\s*[（(]\\s*\\d+\\s*[）)]|照片|图片");

  public boolean isLikelyIncomplete(byte[] pageJpeg, String text, boolean hasVisualContent) {
    if (hasVisualContent) {
      return false;
    }
    String normalized = text == null ? "" : text.replaceAll("\\s+", "");
    if (normalized.length() >= MIN_COMPLETE_CHARS) {
      return false;
    }
    return countTextBands(pageJpeg) >= MIN_DENSE_TEXT_BANDS;
  }

  /**
   * 页面是否存在可见墨迹文本带，用于区分“空白页”与“模型漏输出 lines”。
   */
  public boolean hasVisibleText(byte[] pageJpeg) {
    return countTextBands(pageJpeg) > 0;
  }

  public boolean hasMissingRequiredMarkup(String text) {
    if (text == null) {
      return false;
    }
    return (text.contains("加点") || text.contains("着重号"))
        && !text.contains("{{")
        && !text.contains("[[u:")
        && !text.contains("[[w:");
  }

  public boolean hasMissingVisualAssets(String text, boolean modelReportsFigure, boolean hasAssets) {
    if (hasAssets) {
      return false;
    }
    return modelReportsFigure
        || referencesVisualContent(text);
  }

  public boolean referencesVisualContent(String text) {
    return text != null && VISUAL_REFERENCE.matcher(text).find();
  }

  public boolean referencesStemFigure(String text) {
    return text != null && STEM_REFERENCE.matcher(text).find();
  }

  public boolean hasMissingDeclaredStemFigureAssets(
      List<String> stemFigureAnchors,
      List<PageAsset> assets
  ) {
    return PaperStemFigureConsistency.hasMissing(stemFigureAnchors, assets);
  }

  int countTextBands(byte[] pageJpeg) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(pageJpeg));
      if (image == null) {
        return 0;
      }
      int step = 2;
      int left = image.getWidth() / 20;
      int right = image.getWidth() - left;
      int top = image.getHeight() / 20;
      int bottom = image.getHeight() - top;
      int minDark = Math.max(2, image.getWidth() / 400);
      int bands = 0;
      int lastActiveY = -100;
      for (int y = top; y < bottom; y += step) {
        int dark = 0;
        for (int x = left; x < right; x += step) {
          int rgb = image.getRGB(x, y);
          int r = (rgb >> 16) & 0xFF;
          int g = (rgb >> 8) & 0xFF;
          int b = rgb & 0xFF;
          int luminance = (r * 299 + g * 587 + b * 114) / 1000;
          if (luminance < 185) {
            dark++;
          }
        }
        if (dark >= minDark) {
          if (y - lastActiveY > 6) {
            bands++;
          }
          lastActiveY = y;
        }
      }
      return bands;
    } catch (Exception e) {
      return 0;
    }
  }

}
