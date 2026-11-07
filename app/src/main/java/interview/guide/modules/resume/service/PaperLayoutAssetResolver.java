package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognition.PageAsset;
import interview.guide.modules.resume.model.PageRecognition.PageLine;
import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 使用页面行级布局约束资源 bbox。
 *
 * <p>视觉模型有时把资源框到相邻题目。这里根据 anchorText 定位文字行，
 * 再找到当前题号行和下一题号行，将资源严格限制在本题的纵向行带内。
 * 该约束只缩小跨题错误，不会处理资源内部图形是否完整。</p>
 */
@Component
public class PaperLayoutAssetResolver {

  private static final Pattern QUESTION_START =
      Pattern.compile("^\\s*(?:第\\s*)?(\\d{1,3})(?=\\s|[.．、:：)）]|\\D)");

  public record ResolvedRegion(Rectangle region, Rectangle questionBand) {
  }

  public ResolvedRegion resolve(BufferedImage page, Rectangle assetRect,
                                PageAsset asset, List<PageLine> lines) {
    Rectangle pageBounds = new Rectangle(0, 0, page.getWidth(), page.getHeight());
    if (lines == null || lines.isEmpty()) {
      return new ResolvedRegion(assetRect, pageBounds);
    }
    PageLine anchor = findAnchorLine(asset.anchorText(), assetRect, page, lines);
    if (anchor == null) {
      return new ResolvedRegion(assetRect, pageBounds);
    }
    Rectangle band = questionBand(anchor, lines, page);
    if (band == null) {
      return new ResolvedRegion(assetRect, pageBounds);
    }
    Rectangle constrained = assetRect.intersection(band);
    if (constrained.width < 10 || constrained.height < 10) {
      return new ResolvedRegion(assetRect, pageBounds);
    }
    return new ResolvedRegion(constrained, band);
  }

  private PageLine findAnchorLine(String anchorText, Rectangle assetRect,
                                  BufferedImage page, List<PageLine> lines) {
    String anchor = normalize(anchorText);
    if (anchor.isBlank()) {
      return nearestLine(assetRect, page, lines);
    }
    return lines.stream()
        .filter(line -> line.text() != null && line.bbox() != null && line.bbox().length >= 4)
        .min(Comparator.comparingLong(line -> anchorScore(anchor, assetRect, page, line)))
        .filter(line -> anchorScore(anchor, assetRect, page, line) < 10_000)
        .orElseGet(() -> nearestLine(assetRect, page, lines));
  }

  private long anchorScore(String anchor, Rectangle assetRect,
                           BufferedImage page, PageLine line) {
    String text = normalize(line.text());
    boolean matches = !anchor.isBlank()
        && (text.contains(anchor) || anchor.contains(text) && text.length() >= 4);
    Rectangle lineRect = pixelRect(line, page);
    long verticalDistance = Math.abs(lineRect.getCenterY() - assetRect.getCenterY()) > 0
        ? (long) Math.abs(lineRect.getCenterY() - assetRect.getCenterY()) : 0;
    if (matches) {
      return verticalDistance;
    }
    return 10_000L + verticalDistance;
  }

  private PageLine nearestLine(Rectangle assetRect, BufferedImage page, List<PageLine> lines) {
    return lines.stream()
        .filter(line -> line.bbox() != null && line.bbox().length >= 4)
        .min(Comparator.comparingDouble(line -> {
          Rectangle rect = pixelRect(line, page);
          double dx = rect.getCenterX() - assetRect.getCenterX();
          double dy = rect.getCenterY() - assetRect.getCenterY();
          return dx * dx + dy * dy;
        }))
        .orElse(null);
  }

  private Rectangle questionBand(PageLine anchor, List<PageLine> lines, BufferedImage page) {
    int anchorIndex = lines.indexOf(anchor);
    if (anchorIndex < 0) {
      return null;
    }
    int questionStart = -1;
    for (int i = anchorIndex; i >= 0; i--) {
      String text = lines.get(i).text();
      if (text != null && QUESTION_START.matcher(text).find()) {
        questionStart = i;
        break;
      }
    }
    if (questionStart < 0) {
      return null;
    }
    int nextQuestion = lines.size();
    for (int i = questionStart + 1; i < lines.size(); i++) {
      String text = lines.get(i).text();
      if (text != null && QUESTION_START.matcher(text).find()) {
        nextQuestion = i;
        break;
      }
    }
    Rectangle startRect = pixelRect(lines.get(questionStart), page);
    int top = Math.max(0, startRect.y - 8);
    int bottom = nextQuestion < lines.size()
        ? pixelRect(lines.get(nextQuestion), page).y - 4
        : page.getHeight();
    if (nextQuestion < lines.size() && bottom <= top) {
      // 双栏阅读顺序会从左侧底部跳到右侧顶部。此时题号起始行的 y
      // 已经不可作为右栏资源上界，只用锚点所在续写位置到下一题之间。
      Rectangle anchorRect = pixelRect(anchor, page);
      top = Math.max(0, anchorRect.y - Math.max(8, anchorRect.height));
      bottom = pixelRect(lines.get(nextQuestion), page).y - 4;
    }
    bottom = Math.max(top + 1, bottom);
    return new Rectangle(0, top, page.getWidth(), Math.max(1, bottom - top));
  }

  private Rectangle pixelRect(PageLine line, BufferedImage page) {
    double[] bbox = line.bbox();
    int x = (int) Math.round(Math.max(0, Math.min(1, bbox[0])) * page.getWidth());
    int y = (int) Math.round(Math.max(0, Math.min(1, bbox[1])) * page.getHeight());
    int width = (int) Math.round(Math.min(1 - bbox[0], bbox[2]) * page.getWidth());
    int height = (int) Math.round(Math.min(1 - bbox[1], bbox[3]) * page.getHeight());
    return new Rectangle(x, y, Math.max(1, width), Math.max(1, height));
  }

  private String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "")
        .replaceAll("[，,。.；;：:（）()]", "");
  }
}
