package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognition.PageAsset;
import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 图形选择题的题干图兜底。
 *
 * <p>视觉模型有时只框出 A/B/C/D 选项图，漏掉题干旁的小几何图。
 * 当题干明确写“如图”等引用、且选项上方仍有独立内容区时，
 * 生成一个题干上下文截图，保证图形不会从题目中消失。</p>
 */
@Component
public class PaperMissingStemAssetResolver {

  private static final Pattern STEM_VISUAL_REFERENCE = Pattern.compile(
      "如图|图中|下图|上图|图\\s*[（(]\\s*\\d+");
  private static final int MIN_CONTEXT_HEIGHT = 24;

  public Optional<PageAsset> resolve(
      BufferedImage page,
      Rectangle optionRow,
      Rectangle questionBand,
      String questionText,
      String anchorText,
      boolean declaredStemFigure
  ) {
    boolean referenced = questionText != null
        && STEM_VISUAL_REFERENCE.matcher(questionText).find();
    if (page == null || optionRow == null || questionBand == null
        || !declaredStemFigure && !referenced) {
      return Optional.empty();
    }
    Rectangle bounds = new Rectangle(0, 0, page.getWidth(), page.getHeight());
    Rectangle option = clamp(optionRow.intersection(bounds), page);
    Rectangle band = clamp(questionBand.intersection(bounds), page);
    int top = band.y;
    int bottom = Math.min(option.y - 4, band.y + band.height);
    if (bottom - top < MIN_CONTEXT_HEIGHT) {
      return Optional.empty();
    }
    int horizontalPadding = Math.max(6, Math.round(page.getWidth() * 0.008f));
    int x = Math.max(bounds.x, option.x - horizontalPadding);
    int right = Math.min(bounds.x + bounds.width,
        option.x + option.width + horizontalPadding);
    Rectangle context = new Rectangle(x, top, Math.max(1, right - x), bottom - top);
    return Optional.of(new PageAsset(
        "FIGURE",
        "STEM",
        anchorText,
        normalizedRect(context, page),
        null,
        null,
        List.of()
    ));
  }

  private double[] normalizedRect(Rectangle rect, BufferedImage page) {
    return new double[] {
        (double) rect.x / page.getWidth(),
        (double) rect.y / page.getHeight(),
        (double) rect.width / page.getWidth(),
        (double) rect.height / page.getHeight()
    };
  }

  private Rectangle clamp(Rectangle rect, BufferedImage image) {
    int x = Math.max(0, Math.min(image.getWidth() - 1, rect.x));
    int y = Math.max(0, Math.min(image.getHeight() - 1, rect.y));
    int width = Math.max(1, Math.min(image.getWidth() - x, rect.width));
    int height = Math.max(1, Math.min(image.getHeight() - y, rect.height));
    return new Rectangle(x, y, width, height);
  }
}
