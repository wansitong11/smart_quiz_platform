package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognition.PageAsset;
import interview.guide.modules.resume.model.PageRecognition.PageLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("页面布局资源约束")
class PaperLayoutAssetResolverTest {

  private final PaperLayoutAssetResolver resolver = new PaperLayoutAssetResolver();

  @Test
  @DisplayName("跨越下一题的错误资源框会被限制在当前题目行带")
  void shouldConstrainOversizedAssetToQuestionBand() {
    BufferedImage page = new BufferedImage(600, 1000, BufferedImage.TYPE_INT_RGB);
    List<PageLine> lines = List.of(
        new PageLine("1. 第一题", new double[] {0.05, 0.20, 0.4, 0.03}),
        new PageLine("A B C D", new double[] {0.05, 0.26, 0.8, 0.03}),
        new PageLine("2. 第二题", new double[] {0.05, 0.40, 0.4, 0.03})
    );
    PageAsset asset = new PageAsset(
        "OPTION_ROW", "OPTIONS", "1. 第一题",
        new double[] {0.05, 0.22, 0.8, 0.30}, "1x4", 4, List.of("A", "B", "C", "D"));

    PaperLayoutAssetResolver.ResolvedRegion region = resolver.resolve(
        page, new Rectangle(30, 220, 480, 300), asset, lines);

    assertThat(region.region().y + region.region().height).isLessThanOrEqualTo(396);
    assertThat(region.questionBand().y).isLessThan(220);
    assertThat(region.questionBand().y + region.questionBand().height).isLessThanOrEqualTo(396);
  }

  @Test
  @DisplayName("左侧题续写到右侧栏时只保留右栏本题段，不包含下一题")
  void shouldConstrainCrossColumnQuestionBeforeNextQuestion() {
    BufferedImage page = new BufferedImage(1000, 2000, BufferedImage.TYPE_INT_RGB);
    List<PageLine> lines = List.of(
        new PageLine("4. 第四题", new double[] {0.05, 0.66, 0.35, 0.03}),
        new PageLine("5. 第五题如图所示", new double[] {0.05, 0.78, 0.38, 0.03}),
        new PageLine("叠成正方体后，与“全”相对的汉字是", new double[] {0.53, 0.20, 0.40, 0.03}),
        new PageLine("6. 第六题", new double[] {0.53, 0.38, 0.40, 0.03})
    );
    PageAsset asset = new PageAsset(
        "FIGURE", "STEM", "叠成正方体后，与“全”相对的汉字是",
        new double[] {0.52, 0.20, 0.46, 0.30}, null, null, List.of());

    PaperLayoutAssetResolver.ResolvedRegion region = resolver.resolve(
        page, new Rectangle(520, 400, 460, 600), asset, lines);

    assertThat(region.region().y + region.region().height).isLessThanOrEqualTo(756);
    assertThat(region.questionBand().y + region.questionBand().height).isLessThanOrEqualTo(756);
  }
}
