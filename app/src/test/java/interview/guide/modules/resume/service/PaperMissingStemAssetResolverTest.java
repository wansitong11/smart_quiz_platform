package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognition.PageAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("缺失题干图兜底")
class PaperMissingStemAssetResolverTest {

  private final PaperMissingStemAssetResolver resolver = new PaperMissingStemAssetResolver();

  @Test
  @DisplayName("图形选择题引用如图但无独立题干图时生成题干上下文截图")
  void shouldCreateStemContextForFigureChoiceWithoutStemAsset() {
    BufferedImage page = new BufferedImage(1000, 2000, BufferedImage.TYPE_INT_RGB);

    Optional<PageAsset> asset = resolver.resolve(
        page,
        new Rectangle(50, 700, 480, 180),
        new Rectangle(0, 450, 500, 500),
        "2 如图，正方体的三个侧面分别画有不同图案，它的平面展开图可以是",
        "2 如图，正方体的三个侧面分别画有不同图案",
        false
    );

    assertThat(asset).isPresent();
    assertThat(asset.orElseThrow().kind()).isEqualTo("FIGURE");
    assertThat(asset.orElseThrow().role()).isEqualTo("STEM");
    assertThat(asset.orElseThrow().bbox()[1]).isEqualTo(0.225);
    assertThat(asset.orElseThrow().bbox()[1] + asset.orElseThrow().bbox()[3])
        .isLessThanOrEqualTo(0.35);
    assertThat(asset.orElseThrow().bbox()[0]).isLessThanOrEqualTo(0.05);
    assertThat(asset.orElseThrow().bbox()[0] + asset.orElseThrow().bbox()[2])
        .isLessThanOrEqualTo(0.54);
  }

  @Test
  @DisplayName("普通图形选项题没有题干图引用时不生成重复截图")
  void shouldNotCreateStemContextWithoutVisualReference() {
    BufferedImage page = new BufferedImage(1000, 2000, BufferedImage.TYPE_INT_RGB);

    Optional<PageAsset> asset = resolver.resolve(
        page,
        new Rectangle(50, 700, 480, 180),
        new Rectangle(0, 450, 500, 500),
        "1 下列是正方体展开图的是",
        "1 下列是正方体展开图的是",
        false
    );

    assertThat(asset).isEmpty();
  }

  @Test
  @DisplayName("识图模型明确声明题干图时，即使文字没有“如图”也生成兜底截图")
  void shouldCreateStemContextWhenModelDeclaresStemFigure() {
    BufferedImage page = new BufferedImage(1000, 2000, BufferedImage.TYPE_INT_RGB);

    Optional<PageAsset> asset = resolver.resolve(
        page,
        new Rectangle(50, 700, 480, 180),
        new Rectangle(0, 450, 500, 500),
        "观察下面的几何结构并选择正确展开图",
        "观察下面的几何结构并选择正确展开图",
        true
    );

    assertThat(asset).isPresent();
    assertThat(asset.orElseThrow().kind()).isEqualTo("FIGURE");
    assertThat(asset.orElseThrow().role()).isEqualTo("STEM");
  }
}
