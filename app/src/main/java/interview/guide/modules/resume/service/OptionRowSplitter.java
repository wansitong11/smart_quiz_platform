package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
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
    if (count < 2) {
      return null;
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
      if (image == null) {
        return null;
      }
      int width = image.getWidth();
      int height = image.getHeight();
      int[] ink = inkProfile(image);
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
      log.warn("选项带切分失败: {}", e.getMessage());
      return null;
    }
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
}
