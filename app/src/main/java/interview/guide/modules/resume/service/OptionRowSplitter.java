package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.log.PaperFlowLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把一条"选项带"图片按墨迹投影切成 N 块。
 * <p>
 * 不依赖模型报精确坐标：模型只框范围，切线由像素分布决定。
 * </p>
 */
@Slf4j
@Component
public class OptionRowSplitter {

  public record SplitResult(List<byte[]> tiles, List<Double> cutOffsets) {
  }

  /**
   * @param imageBytes 选项带图片
   * @param count      选项数
   * @return 切分结果；无法可靠切分时返回 null（调用方降级为整条带）
   */
  public SplitResult split(byte[] imageBytes, int count) {
    return split(imageBytes, count, null);
  }

  /**
   * @param layout 模型标注的排列，如 1x4 / 2x2；用于限制选项带的最大高度
   */
  public SplitResult split(byte[] imageBytes, int count, String layout) {
    if (count < 2) {
      return null;
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
      if (image == null) {
        return null;
      }
      image = trimToOptionBand(image, layout);
      int width = image.getWidth();
      int height = image.getHeight();
      int[] ink = inkProfile(image);
      List<Integer> whitespaceCuts = whitespaceCuts(ink, height, width, count);
      if (!whitespaceCuts.isEmpty() && looksBalanced(whitespaceCuts, width, count)) {
        return crop(image, whitespaceCuts);
      }
      int[] smooth = smooth(ink, Math.max(3, width / (count * 10)));
      int max = 0;
      for (int value : smooth) {
        max = Math.max(max, value);
      }
      if (max == 0) {
        return null;
      }

      List<Integer> candidates = new ArrayList<>();
      for (int x = Math.max(2, width / 20); x < width - Math.max(2, width / 20); x++) {
        if (smooth[x] <= max * 0.15
            && smooth[x] <= smooth[x - 1]
            && smooth[x] <= smooth[x + 1]
            && (candidates.isEmpty() || x - candidates.get(candidates.size() - 1) > width / (count * 3))) {
          candidates.add(x);
        }
      }
      if (candidates.isEmpty()) {
        return equalSplit(image, count);
      }

      List<Integer> cuts = new ArrayList<>();
      for (int i = 1; i < count; i++) {
        double target = width * (double) i / count;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int candidate : candidates) {
          if (cuts.contains(candidate)) {
            continue;
          }
          double distance = Math.abs(candidate - target);
          if (distance < bestDistance) {
            bestDistance = distance;
            best = candidate;
          }
        }
        if (best < 0) {
          return equalSplit(image, count);
        }
        cuts.add(best);
      }
      cuts.sort(Integer::compareTo);
      if (!looksBalanced(cuts, width, count)) {
        return equalSplit(image, count);
      }

      return crop(image, cuts);
    } catch (Exception e) {
      PaperFlowLog.error("OPTION_ROW_SPLIT_FAILED imageBytes={}, count={}, layout={}",
          e, imageBytes == null ? 0 : imageBytes.length, count, layout);
      log.warn("选项带切分失败: {}", e.getMessage());
      return null;
    }
  }

  /**
   * 选项之间通常存在整列空白。优先在这些空白列中心下刀，
   * 比按整图等分更可靠；扫描噪声较大或图形相连时再走墨迹投影兜底。
   */
  private List<Integer> whitespaceCuts(int[] ink, int height, int width, int count) {
    int lowInk = Math.max(1, height / 100);
    int minGapWidth = Math.max(2, width / 100);
    List<int[]> gaps = new ArrayList<>();
    int runStart = -1;
    for (int x = 0; x < width; x++) {
      if (ink[x] <= lowInk) {
        if (runStart < 0) {
          runStart = x;
        }
      } else if (runStart >= 0) {
        addGap(gaps, runStart, x - 1, minGapWidth);
        runStart = -1;
      }
    }
    if (runStart >= 0) {
      addGap(gaps, runStart, width - 1, minGapWidth);
    }
    if (gaps.size() < count - 1) {
      return List.of();
    }

    List<Integer> cuts = new ArrayList<>();
    for (int i = 1; i < count; i++) {
      int target = (int) Math.round(width * (double) i / count);
      int best = -1;
      int bestDistance = Integer.MAX_VALUE;
      for (int[] gap : gaps) {
        int center = (gap[0] + gap[1]) / 2;
        if (cuts.contains(center)) {
          continue;
        }
        int distance = Math.abs(center - target);
        if (distance < bestDistance) {
          bestDistance = distance;
          best = center;
        }
      }
      if (best < 0) {
        return List.of();
      }
      cuts.add(best);
    }
    cuts.sort(Integer::compareTo);
    return cuts;
  }

  private void addGap(List<int[]> gaps, int start, int end, int minGapWidth) {
    if (end - start + 1 >= minGapWidth) {
      gaps.add(new int[] {start, end});
    }
  }

  /**
   * 视觉模型偶尔把下面几道题也框进 OPTION_ROW。选项图是横向图带，
   * 通过图中连续的竖线定位真正的选项图形区域，避免把上方题干和下方下一题一起切进选项。
   */
  private BufferedImage trimToOptionBand(BufferedImage image, String layout) {
    double heightRatio = layout != null && layout.toLowerCase().contains("2x") ? 1.05 : 0.42;
    int maxHeight = Math.max(1, (int) Math.round(image.getWidth() * heightRatio));
    VerticalBand band = detectVerticalOptionBand(image, maxHeight);
    if (band == null) {
      if (image.getHeight() <= maxHeight) {
        return image;
      }
      log.warn("选项带未找到图形边界，按最大高度收紧: width={}, height={}, maxHeight={}, layout={}",
          image.getWidth(), image.getHeight(), maxHeight, layout);
      return image.getSubimage(0, 0, image.getWidth(), maxHeight);
    }
    int topPadding = Math.max(6, image.getWidth() / 50);
    int bottomPadding = Math.max(topPadding, image.getWidth() / 20);
    int startY = Math.max(0, band.startY() - topPadding);
    int endY = Math.min(image.getHeight(), band.endY() + bottomPadding);
    if (endY - startY > maxHeight) {
      endY = Math.min(image.getHeight(), startY + maxHeight);
    }
    if (startY == 0 && endY == image.getHeight()) {
      return image;
    }
    log.debug("选项带按图形边界收紧: width={}, height={}, band={}-{}, layout={}",
        image.getWidth(), image.getHeight(), startY, endY, layout);
    return image.getSubimage(0, startY, image.getWidth(), Math.max(1, endY - startY));
  }

  private VerticalBand detectVerticalOptionBand(BufferedImage image, int maxHeight) {
    int width = image.getWidth();
    int height = image.getHeight();
    int minVerticalRun = Math.max(12, width / 30);
    List<VerticalRun> runs = new ArrayList<>();
    for (int x = 0; x < width; x++) {
      int run = 0;
      int best = 0;
      int bestStart = 0;
      for (int y = 0; y < height; y++) {
        if (isDark(image.getRGB(x, y))) {
          run++;
          if (run > best) {
            best = run;
            bestStart = y - run + 1;
          }
        } else {
          run = 0;
        }
      }
      if (best >= minVerticalRun) {
        runs.add(new VerticalRun(x, bestStart, bestStart + best - 1, best));
      }
    }
    if (runs.isEmpty()) {
      return null;
    }

    runs.sort(Comparator.comparingInt(VerticalRun::startY));
    List<List<VerticalRun>> clusters = new ArrayList<>();
    List<VerticalRun> current = new ArrayList<>();
    int previousStart = Integer.MIN_VALUE;
    int maxGap = Math.max(10, width / 30);
    for (VerticalRun run : runs) {
      if (!current.isEmpty() && run.startY() - previousStart > maxGap) {
        clusters.add(current);
        current = new ArrayList<>();
      }
      current.add(run);
      previousStart = run.startY();
    }
    if (!current.isEmpty()) {
      clusters.add(current);
    }

    VerticalBand best = null;
    long bestScore = Long.MIN_VALUE;
    for (List<VerticalRun> cluster : clusters) {
      int minY = cluster.stream().mapToInt(VerticalRun::startY).min().orElse(0);
      int maxY = cluster.stream().mapToInt(VerticalRun::endY).max().orElse(0);
      if (maxY - minY < Math.max(12, width / 40) || maxY - minY > maxHeight) {
        continue;
      }
      int[] quarterRuns = new int[4];
      int longRunPixels = 0;
      for (VerticalRun run : cluster) {
        int quarter = Math.min(3, run.x() * 4 / width);
        quarterRuns[quarter]++;
        longRunPixels += run.length();
      }
      int coveredQuarters = 0;
      int minQuarterRuns = Integer.MAX_VALUE;
      for (int quarterRun : quarterRuns) {
        if (quarterRun > 0) {
          coveredQuarters++;
        }
        minQuarterRuns = Math.min(minQuarterRuns, quarterRun);
      }
      long score = coveredQuarters * 1_000_000L
          + minQuarterRuns * 1_000L
          + longRunPixels * 3L;
      if (score > bestScore) {
        bestScore = score;
        best = new VerticalBand(minY, maxY);
      }
    }
    return best;
  }

  private boolean isDark(int rgb) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;
    int luminance = (r * 299 + g * 587 + b * 114) / 1000;
    return luminance < 170;
  }

  /**
   * 等宽兜底：印刷卷的 A/B/C/D 通常等分排列。切完要求每块都有墨迹，否则返回 null 降级。
   */
  private SplitResult equalSplit(BufferedImage image, int count) {
    int width = image.getWidth();
    List<Integer> cuts = new ArrayList<>();
    for (int i = 1; i < count; i++) {
      cuts.add((int) Math.round(width * (double) i / count));
    }
    for (int i = 0; i <= cuts.size(); i++) {
      int start = i == 0 ? 0 : cuts.get(i - 1);
      int end = i == cuts.size() ? width : cuts.get(i);
      if (!hasInk(image.getSubimage(start, 0, Math.max(1, end - start), image.getHeight()))) {
        return null;
      }
    }
    return crop(image, cuts);
  }

  private boolean hasInk(BufferedImage tile) {
    int dark = 0;
    int total = tile.getWidth() * tile.getHeight();
    for (int x = 0; x < tile.getWidth(); x += 2) {
      for (int y = 0; y < tile.getHeight(); y += 2) {
        int rgb = tile.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int luminance = (r * 299 + g * 587 + b * 114) / 1000;
        if (luminance < 190) {
          dark++;
        }
      }
    }
    return dark > total / 4 * 0.0025;
  }

  private SplitResult crop(BufferedImage image, List<Integer> cuts) {
    int width = image.getWidth();
    int height = image.getHeight();
    List<byte[]> tiles = new ArrayList<>();
    List<Double> offsets = new ArrayList<>();
    int previous = 0;
    try {
      for (int i = 0; i <= cuts.size(); i++) {
        int next = i < cuts.size() ? cuts.get(i) : width;
        BufferedImage tile = image.getSubimage(previous, 0, Math.max(1, next - previous), height);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(tile, "jpg", out);
        tiles.add(out.toByteArray());
        if (i < cuts.size()) {
          offsets.add((double) next / width);
        }
        previous = next;
      }
      return new SplitResult(tiles, offsets);
    } catch (Exception e) {
      PaperFlowLog.error("OPTION_ROW_CROP_FAILED width={}, height={}, cuts={}",
          e, image.getWidth(), image.getHeight(), cuts);
      log.warn("选项裁片输出失败: {}", e.getMessage());
      return null;
    }
  }

  private boolean looksBalanced(List<Integer> cuts, int width, int count) {
    double average = (double) width / count;
    int previous = 0;
    for (int i = 0; i <= cuts.size(); i++) {
      int next = i < cuts.size() ? cuts.get(i) : width;
      double segment = next - previous;
      if (segment < average * 0.4 || segment > average * 1.8) {
        return false;
      }
      previous = next;
    }
    return true;
  }

  private int[] inkProfile(BufferedImage image) {
    int width = image.getWidth();
    int height = image.getHeight();
    int[] ink = new int[width];
    for (int x = 0; x < width; x++) {
      int dark = 0;
      for (int y = 0; y < height; y++) {
        int rgb = image.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int luminance = (r * 299 + g * 587 + b * 114) / 1000;
        if (luminance < 190) {
          dark++;
        }
      }
      ink[x] = dark;
    }
    return ink;
  }

  private int[] smooth(int[] values, int window) {
    int[] result = new int[values.length];
    for (int i = 0; i < values.length; i++) {
      int sum = 0;
      int count = 0;
      for (int k = -window; k <= window; k++) {
        int index = i + k;
        if (index >= 0 && index < values.length) {
          sum += values[index];
          count++;
        }
      }
      result[i] = count == 0 ? 0 : sum / count;
    }
    return result;
  }

  private record VerticalRun(int x, int startY, int endY, int length) {
  }

  private record VerticalBand(int startY, int endY) {
  }
}
