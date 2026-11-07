package interview.guide.modules.resume.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("选项行图片切分")
class OptionRowSplitterTest {

  private final OptionRowSplitter splitter = new OptionRowSplitter();

  @Test
  @DisplayName("异常高的选项行先纵向收紧，排除下一题内容")
  void shouldTrimExcessHeightBeforeHorizontalSplit() throws Exception {
    byte[] source = rowImage(400, 1000);

    OptionRowSplitter.SplitResult result = splitter.split(source, 4, "1x4");

    assertThat(result).isNotNull();
    assertThat(result.tiles()).hasSize(4);
    for (byte[] tile : result.tiles()) {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(tile));
      assertThat(image.getHeight()).isBetween(80, (int) Math.round(400 * 0.42));
    }
  }

  @Test
  @DisplayName("选项图形不按四等分时，优先从整列空白处切开")
  void shouldCutAtWhitespaceInsteadOfEqualQuarters() throws Exception {
    byte[] source = unevenRowImage();

    OptionRowSplitter.SplitResult result = splitter.split(source, 4, "1x4");

    assertThat(result).isNotNull();
    assertThat(result.cutOffsets()).hasSize(3);
    assertThat(result.cutOffsets().get(0)).isCloseTo(147.5 / 612, within(0.02));
    assertThat(result.cutOffsets().get(1)).isCloseTo(277.5 / 612, within(0.02));
    assertThat(result.cutOffsets().get(2)).isCloseTo(412.5 / 612, within(0.02));
  }

  private byte[] rowImage(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, width, height);
    graphics.setColor(Color.BLACK);
    for (int i = 0; i < 4; i++) {
      int x = i * width / 4 + width / 16;
      graphics.drawRect(x, 30, width / 8, 80);
      graphics.drawRect(x, 140, width / 8, 8);
      // Simulate the next question that must be trimmed away.
      graphics.drawLine(10, 500, width - 10, 500);
    }
    graphics.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private byte[] unevenRowImage() throws Exception {
    BufferedImage image = new BufferedImage(612, 220, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 612, 220);
    graphics.setColor(Color.BLACK);
    int[][] optionBounds = {
        {10, 140},
        {155, 270},
        {285, 405},
        {420, 540}
    };
    for (int[] bounds : optionBounds) {
      graphics.drawRect(bounds[0], 60, bounds[1] - bounds[0], 90);
    }
    graphics.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }
}
