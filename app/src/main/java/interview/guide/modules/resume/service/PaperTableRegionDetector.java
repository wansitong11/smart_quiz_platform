package interview.guide.modules.resume.service;

import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从视觉模型给出的粗 bbox 中检测独立表格外框。
 *
 * <p>扫描件中的表格/名片通常由连续的黑色边框组成；文字、下划线和阴影块的连通区域
 * 小于完整表格。这里通过连通域找外框，从而把相邻名片或其他表格拆成独立资源。</p>
 */
@Component
public class PaperTableRegionDetector {

  public List<Rectangle> detect(BufferedImage page, Rectangle searchArea) {
    Rectangle area = clamp(searchArea, page.getWidth(), page.getHeight());
    if (area.width < 40 || area.height < 30) {
      return List.of();
    }
    int[] pixels = page.getRGB(area.x, area.y, area.width, area.height,
        null, 0, area.width);
    boolean[] ink = new boolean[pixels.length];
    for (int i = 0; i < pixels.length; i++) {
      int rgb = pixels[i];
      int r = (rgb >> 16) & 0xFF;
      int g = (rgb >> 8) & 0xFF;
      int b = rgb & 0xFF;
      int luminance = (r * 299 + g * 587 + b * 114) / 1000;
      ink[i] = luminance < 190;
    }

    boolean[] joined = dilate(ink, area.width, area.height);
    boolean[] visited = new boolean[joined.length];
    int[] queue = new int[joined.length];
    List<Rectangle> regions = new ArrayList<>();
    int minWidth = Math.max(80, (int) Math.round(area.width * 0.35));
    int minHeight = Math.max(30, (int) Math.round(area.height * 0.06));

    for (int start = 0; start < joined.length; start++) {
      if (!joined[start] || visited[start]) {
        continue;
      }
      int head = 0;
      int tail = 0;
      queue[tail++] = start;
      visited[start] = true;
      int minX = area.width;
      int minY = area.height;
      int maxX = -1;
      int maxY = -1;
      while (head < tail) {
        int index = queue[head++];
        int x = index % area.width;
        int y = index / area.width;
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        for (int dy = -1; dy <= 1; dy++) {
          int nextY = y + dy;
          if (nextY < 0 || nextY >= area.height) {
            continue;
          }
          for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) {
              continue;
            }
            int nextX = x + dx;
            if (nextX < 0 || nextX >= area.width) {
              continue;
            }
            int next = nextY * area.width + nextX;
            if (joined[next] && !visited[next]) {
              visited[next] = true;
              queue[tail++] = next;
            }
          }
        }
      }
      int width = maxX - minX + 1;
      int height = maxY - minY + 1;
      if (width >= minWidth && height >= minHeight) {
        regions.add(new Rectangle(area.x + minX, area.y + minY, width, height));
      }
    }
    return deduplicate(regions, page.getWidth(), page.getHeight());
  }

  private boolean[] dilate(boolean[] source, int width, int height) {
    boolean[] expanded = new boolean[source.length];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (!source[y * width + x]) {
          continue;
        }
        for (int dy = -1; dy <= 1; dy++) {
          int nextY = y + dy;
          if (nextY < 0 || nextY >= height) {
            continue;
          }
          for (int dx = -1; dx <= 1; dx++) {
            int nextX = x + dx;
            if (nextX >= 0 && nextX < width) {
              expanded[nextY * width + nextX] = true;
            }
          }
        }
      }
    }
    return expanded;
  }

  private List<Rectangle> deduplicate(List<Rectangle> regions, int pageWidth, int pageHeight) {
    List<Rectangle> sorted = regions.stream()
        .sorted(Comparator.comparingInt((Rectangle rect) -> rect.y)
            .thenComparingInt(rect -> rect.x))
        .toList();
    List<Rectangle> result = new ArrayList<>();
    for (Rectangle candidate : sorted) {
      Rectangle padded = new Rectangle(
          Math.max(0, candidate.x - 4),
          Math.max(0, candidate.y - 4),
          Math.min(pageWidth - Math.max(0, candidate.x - 4), candidate.width + 8),
          Math.min(pageHeight - Math.max(0, candidate.y - 4), candidate.height + 8)
      );
      boolean duplicate = result.stream().anyMatch(existing -> overlapRatio(existing, padded) > 0.8);
      if (!duplicate) {
        result.add(padded);
      }
    }
    return List.copyOf(result);
  }

  private double overlapRatio(Rectangle left, Rectangle right) {
    Rectangle intersection = left.intersection(right);
    if (intersection.isEmpty()) {
      return 0;
    }
    double intersectionArea = intersection.getWidth() * intersection.getHeight();
    double smallerArea = Math.min(left.getWidth() * left.getHeight(),
        right.getWidth() * right.getHeight());
    return smallerArea == 0 ? 0 : intersectionArea / smallerArea;
  }

  private Rectangle clamp(Rectangle area, int pageWidth, int pageHeight) {
    int x = Math.max(0, Math.min(pageWidth - 1, area.x));
    int y = Math.max(0, Math.min(pageHeight - 1, area.y));
    int width = Math.max(1, Math.min(pageWidth - x, area.width));
    int height = Math.max(1, Math.min(pageHeight - y, area.height));
    return new Rectangle(x, y, width, height);
  }
}
