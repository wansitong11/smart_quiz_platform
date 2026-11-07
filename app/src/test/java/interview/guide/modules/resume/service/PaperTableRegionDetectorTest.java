package interview.guide.modules.resume.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("表格资源区域检测")
class PaperTableRegionDetectorTest {

  private final PaperTableRegionDetector detector = new PaperTableRegionDetector();

  @Test
  @DisplayName("一个粗 bbox 内包含两张相邻名片时拆成两个资源")
  void shouldSplitAdjacentTables() {
    BufferedImage page = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = page.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 1000, 700);
    graphics.setColor(Color.BLACK);
    graphics.setStroke(new BasicStroke(3f));
    graphics.drawRect(60, 50, 840, 220);
    graphics.drawLine(60, 110, 900, 110);
    graphics.drawRect(60, 300, 840, 220);
    graphics.drawLine(60, 360, 900, 360);
    graphics.dispose();

    List<Rectangle> regions = detector.detect(page, new Rectangle(40, 40, 880, 500));

    assertThat(regions).hasSize(2);
    assertThat(regions.get(0).getCenterY()).isLessThan(regions.get(1).getCenterY());
    assertThat(regions.get(0).width).isGreaterThan(800);
    assertThat(regions.get(1).width).isGreaterThan(800);
  }
}
