package interview.guide.modules.resume.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("视觉资源墨迹块解析")
class PaperVisualBlockResolverTest {

  private final PaperVisualBlockResolver resolver = new PaperVisualBlockResolver();

  @Test
  @DisplayName("选项图模型框上移或下移时恢复完整选项并排除下一题文字")
  void shouldRecoverCompleteOptionBlockAndExcludeNextQuestion() {
    BufferedImage page = optionRowPage();

    Rectangle resolved = resolver.resolve(
        page,
        new Rectangle(40, 360, 720, 280),
        new Rectangle(0, 250, 800, 450)
    );

    assertThat(resolved.y).as("resolved=%s", resolved).isLessThanOrEqualTo(300);
    assertThat(resolved.y + resolved.height).as("resolved=%s", resolved).isBetween(540, 590);
    assertThat(resolved.x).isLessThanOrEqualTo(40);
    assertThat(resolved.x + resolved.width).isGreaterThanOrEqualTo(760);
  }

  private BufferedImage optionRowPage() {
    BufferedImage page = new BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = page.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 800, 1200);
    graphics.setColor(Color.BLACK);
    for (int option = 0; option < 4; option++) {
      int x = 50 + option * 180;
      graphics.drawRect(x, 300, 120, 200);
      graphics.drawRect(x + 40, 300, 60, 120);
      graphics.drawString(String.valueOf((char) ('A' + option)), x + 50, 540);
    }
    graphics.drawString("4 如图，在方格中画阴影", 55, 660);
    graphics.drawString("图(1)                           图(2)", 80, 690);
    graphics.dispose();
    return page;
  }
}
