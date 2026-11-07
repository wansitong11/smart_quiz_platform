package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognition.PageAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("页面识别完整性校验")
class PageRecognitionValidatorTest {

  private final PageRecognitionValidator validator = new PageRecognitionValidator();

  @Test
  @DisplayName("高密度文字页只返回一行时判定为不完整")
  void shouldRejectSingleLineFromDensePage() throws Exception {
    assertThat(validator.isLikelyIncomplete(
        denseTextPage(), "3. 下列句子中，加点的关联词语使用不正确的一项是（ ）。", false))
        .isTrue();
  }

  @Test
  @DisplayName("有表格或图形资源的短文字页不作为漏读判断")
  void shouldSkipShortTextWhenPageHasFigure() throws Exception {
    assertThat(validator.isLikelyIncomplete(denseTextPage(), "第6页，共8页", true))
        .isFalse();
  }

  @Test
  @DisplayName("正常长文本不触发重试")
  void shouldAcceptLongText() throws Exception {
    assertThat(validator.isLikelyIncomplete(denseTextPage(), "完整页面文字".repeat(30), false))
        .isFalse();
  }

  @Test
  @DisplayName("出现加点要求但没有标记时触发格式校验")
  void shouldDetectMissingEmphasisMarkup() {
    assertThat(validator.hasMissingRequiredMarkup("1. 给下列加点字选择正确读音"))
        .isTrue();
    assertThat(validator.hasMissingRequiredMarkup("1. 给下列{{加点}}字选择正确读音"))
        .isFalse();
  }

  @Test
  @DisplayName("文字明确引用图形但页面没有视觉资源时判定为不完整")
  void shouldDetectMissingVisualAssets() {
    String text = "3. 如图，正方体的展开图是（ ）。\n图(1) 图(2)";

    assertThat(validator.hasMissingVisualAssets(text, false, false)).isTrue();
    assertThat(validator.hasMissingVisualAssets(text, false, true)).isFalse();
  }

  @Test
  @DisplayName("模型自报页面有图但未返回资源时判定为不完整")
  void shouldDetectModelReportedFigureWithoutAssets() {
    assertThat(validator.hasMissingVisualAssets("A B C D", true, false)).isTrue();
    assertThat(validator.hasMissingVisualAssets("A B C D", false, false)).isFalse();
  }

  @Test
  @DisplayName("模型声明题干图但未返回对应 FIGURE 资源时判定为不完整")
  void shouldDetectDeclaredStemFigureWithoutMatchingAsset() {
    String anchor = "2 如图，正方体的三个侧面分别画有不同图案";
    PageAsset optionRow = new PageAsset(
        "OPTION_ROW", "OPTIONS", anchor,
        new double[] {0.05, 0.35, 0.48, 0.10}, "1x4", 4, List.of("A", "B", "C", "D"));

    assertThat(validator.hasMissingDeclaredStemFigureAssets(
        List.of(anchor), List.of(optionRow))).isTrue();
    assertThat(validator.hasMissingDeclaredStemFigureAssets(
        List.of(anchor), List.of(optionRow, new PageAsset(
            "FIGURE", "STEM", anchor,
            new double[] {0.45, 0.27, 0.08, 0.08}, null, null, List.of())))).isFalse();
  }

  private byte[] denseTextPage() throws Exception {
    BufferedImage image = new BufferedImage(1000, 1400, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 1000, 1400);
    graphics.setColor(Color.BLACK);
    for (int line = 0; line < 28; line++) {
      int y = 80 + line * 42;
      graphics.drawLine(100, y, 880, y);
      graphics.drawLine(120, y - 16, 600, y - 16);
    }
    graphics.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", out);
    return out.toByteArray();
  }
}
