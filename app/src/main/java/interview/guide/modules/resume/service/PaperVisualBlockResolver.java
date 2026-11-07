package interview.guide.modules.resume.service;

import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按页面墨迹恢复视觉资源边界。
 *
 * <p>视觉模型给出的 bbox 只能表示“资源大概在哪里”，会产生上下偏移。
 * 这里在题目行带内找与候选框重叠最多、由连续墨迹行组成的块，
 * 再恢复完整高度；不跨过题目行带，也不把下一题文字带进来。</p>
 */
@Component
public class PaperVisualBlockResolver {

  private static final int MIN_HEIGHT = 12;
  private static final int PADDING = 4;

  public Rectangle resolve(BufferedImage page, Rectangle candidate, Rectangle boundary) {
    Rectangle pageBounds = new Rectangle(0, 0, page.getWidth(), page.getHeight());
    Rectangle search = clamp(boundary.intersection(pageBounds), page);
    Rectangle seed = clamp(candidate.intersection(search), page);
    if (search.width < 10 || search.height < MIN_HEIGHT || seed.width < 10) {
      return candidate.intersection(pageBounds);
    }

    int xStart = Math.max(search.x, seed.x);
    int xEnd = Math.min(search.x + search.width, seed.x + seed.width);
    int threshold = Math.max(2, (xEnd - xStart) / 500);
    List<IntRange> inkRuns = inkRuns(page, xStart, xEnd, search.y, search.y + search.height, threshold);
    if (inkRuns.isEmpty()) {
      return seed;
    }

    int gapTolerance = Math.max(24, Math.round(page.getHeight() * 0.04f));
    List<IntRange> blocks = merge(inkRuns, gapTolerance);
    IntRange selected = blocks.stream()
        .max(Comparator
            .comparingInt((IntRange block) -> overlap(block, seed))
            .thenComparingInt(IntRange::length))
        .orElse(inkRuns.getFirst());
    if (selected.length() < MIN_HEIGHT) {
      return seed;
    }

    int top = Math.max(search.y, selected.start() - PADDING);
    int bottom = Math.min(search.y + search.height, selected.end() + PADDING);
    int x = Math.max(search.x, seed.x - PADDING);
    int right = Math.min(search.x + search.width, seed.x + seed.width + PADDING);
    return new Rectangle(x, top, Math.max(1, right - x), Math.max(1, bottom - top));
  }

  private List<IntRange> inkRuns(BufferedImage page, int xStart, int xEnd,
                                 int yStart, int yEnd, int threshold) {
    List<IntRange> runs = new ArrayList<>();
    int activeStart = -1;
    for (int y = yStart; y < yEnd; y++) {
      int dark = 0;
      for (int x = xStart; x < xEnd; x += 2) {
        if (isDark(page.getRGB(x, y))) {
          dark++;
        }
      }
      if (dark >= threshold) {
        if (activeStart < 0) {
          activeStart = y;
        }
      } else if (activeStart >= 0) {
        runs.add(new IntRange(activeStart, y));
        activeStart = -1;
      }
    }
    if (activeStart >= 0) {
      runs.add(new IntRange(activeStart, yEnd));
    }
    return runs;
  }

  private List<IntRange> merge(List<IntRange> runs, int maxGap) {
    List<IntRange> merged = new ArrayList<>();
    IntRange current = runs.getFirst();
    for (int i = 1; i < runs.size(); i++) {
      IntRange next = runs.get(i);
      if (next.start() - current.end() <= maxGap) {
        current = new IntRange(current.start(), next.end());
      } else {
        merged.add(current);
        current = next;
      }
    }
    merged.add(current);
    return merged;
  }

  private int overlap(IntRange range, Rectangle rect) {
    return Math.max(0,
        Math.min(range.end(), rect.y + rect.height) - Math.max(range.start(), rect.y));
  }

  private boolean isDark(int rgb) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;
    return (r * 299 + g * 587 + b * 114) / 1000 < 205;
  }

  private Rectangle clamp(Rectangle rect, BufferedImage image) {
    int x = Math.max(0, Math.min(image.getWidth() - 1, rect.x));
    int y = Math.max(0, Math.min(image.getHeight() - 1, rect.y));
    int width = Math.max(1, Math.min(image.getWidth() - x, rect.width));
    int height = Math.max(1, Math.min(image.getHeight() - y, rect.height));
    return new Rectangle(x, y, width, height);
  }

  private record IntRange(int start, int end) {
    int length() {
      return end - start;
    }
  }
}
