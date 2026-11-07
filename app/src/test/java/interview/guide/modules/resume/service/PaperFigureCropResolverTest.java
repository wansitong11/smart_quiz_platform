package interview.guide.modules.resume.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("题干图形 bbox 安全扩张")
class PaperFigureCropResolverTest {

  private final PaperFigureCropResolver resolver = new PaperFigureCropResolver();

  @Test
  @DisplayName("图形线条接触 cut 边界时向该方向扩张到线条结束")
  void shouldExpandUntilClippedLineEnds() {
    BufferedImage page = new BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = page.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 500, 500);
    graphics.setColor(Color.BLACK);
    graphics.drawRect(120, 40, 220, 220);
    graphics.drawLine(240, 40, 240, 260);
    graphics.dispose();

    Rectangle expanded = resolver.expand(page, new Rectangle(100, 160, 300, 120));

    assertThat(expanded.y).isLessThan(160);
    assertThat(expanded.y + expanded.height).isGreaterThanOrEqualTo(260);
    assertThat(expanded.x).isLessThanOrEqualTo(120);
  }
}
