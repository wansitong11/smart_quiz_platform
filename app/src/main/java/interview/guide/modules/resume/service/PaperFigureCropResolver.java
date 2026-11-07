package interview.guide.modules.resume.service;

import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * 题干图形 bbox 的安全扩张器。
 *
 * <p>视觉模型经常把几何图的顶部/侧边压到 bbox 边界，导致裁图时直接切断线段。
 * 这里只向图形仍接触的方向扩张，直到新的边界没有墨迹；不做任何向内裁剪，
 * 因此不会影响已经完整的图形。</p>
 */
@Component
public class PaperFigureCropResolver {

  private static final int STEP = 8;

  public Rectangle expand(BufferedImage page, Rectangle initial) {
    return expand(page, initial, new Rectangle(0, 0, page.getWidth(), page.getHeight()));
  }

  public Rectangle expand(BufferedImage page, Rectangle initial, Rectangle boundary) {
    Rectangle safeBoundary = clamp(boundary, page);
    Rectangle result = clamp(initial.intersection(safeBoundary), page);
    int maxHorizontal = Math.max(40, initial.width / 4);
    int maxTop = Math.max(80, initial.height * 2);
    int maxBottom = Math.max(60, initial.height);

    result = expandDirection(page, result, safeBoundary, Direction.TOP, maxTop);
    result = expandDirection(page, result, safeBoundary, Direction.BOTTOM, maxBottom);
    result = expandDirection(page, result, safeBoundary, Direction.LEFT, maxHorizontal);
    result = expandDirection(page, result, safeBoundary, Direction.RIGHT, maxHorizontal);
    return result;
  }

  private Rectangle expandDirection(BufferedImage page, Rectangle initial,
                                    Rectangle boundary, Direction direction, int maxExpansion) {
    Rectangle current = new Rectangle(initial);
    int expanded = 0;
    while (expanded < maxExpansion && edgeHasInk(page, current, direction)) {
      Rectangle next = switch (direction) {
        case TOP -> {
          int growth = Math.min(STEP, current.y - boundary.y);
          yield new Rectangle(current.x, current.y - growth, current.width, current.height + growth);
        }
        case BOTTOM -> {
          int growth = Math.min(STEP,
              boundary.y + boundary.height - current.y - current.height);
          yield new Rectangle(current.x, current.y, current.width, current.height + growth);
        }
        case LEFT -> {
          int growth = Math.min(STEP, current.x - boundary.x);
          yield new Rectangle(current.x - growth, current.y, current.width + growth, current.height);
        }
        case RIGHT -> {
          int growth = Math.min(STEP,
              boundary.x + boundary.width - current.x - current.width);
          yield new Rectangle(current.x, current.y, current.width + growth, current.height);
        }
      };
      if (next.equals(current)) {
        break;
      }
      current = next;
      expanded += STEP;
    }
    return current;
  }

  private boolean edgeHasInk(BufferedImage page, Rectangle rect, Direction direction) {
    if (direction == Direction.TOP || direction == Direction.BOTTOM) {
      int y = direction == Direction.TOP ? rect.y : rect.y + rect.height - 1;
      for (int x = rect.x; x < rect.x + rect.width; x += 2) {
        if (isDark(page.getRGB(x, y))) {
          return true;
        }
      }
      return false;
    }
    int x = direction == Direction.LEFT ? rect.x : rect.x + rect.width - 1;
    for (int y = rect.y; y < rect.y + rect.height; y += 2) {
      if (isDark(page.getRGB(x, y))) {
        return true;
      }
    }
    return false;
  }

  private boolean isDark(int rgb) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;
    int luminance = (r * 299 + g * 587 + b * 114) / 1000;
    return luminance < 200;
  }

  private Rectangle clamp(Rectangle rect, BufferedImage image) {
    int x = Math.max(0, Math.min(image.getWidth() - 1, rect.x));
    int y = Math.max(0, Math.min(image.getHeight() - 1, rect.y));
    int width = Math.max(1, Math.min(image.getWidth() - x, rect.width));
    int height = Math.max(1, Math.min(image.getHeight() - y, rect.height));
    return new Rectangle(x, y, width, height);
  }

  private enum Direction {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT
  }
}
