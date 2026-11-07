package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognition.PageAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("页面几何换算")
class PageGeometryTest {

  @Test
  @DisplayName("资源归一化坐标按页面尺寸换算为像素区域")
  void convertsNormalizedAssetRectToPixels() {
    PageAsset asset = new PageAsset(
        "FIGURE", "STEM", null, new double[] {0.1, 0.2, 0.2, 0.3},
        null, null, List.of());

    assertThat(PageGeometry.pixelRect(asset, 1000, 2000))
        .isEqualTo(new Rectangle(100, 400, 200, 600));
  }

  @Test
  @DisplayName("模型返回像素坐标时自动按页面尺寸换算")
  void convertsPixelAssetRectToPixels() {
    PageAsset asset = new PageAsset(
        "FIGURE", "STEM", null, new double[] {100, 400, 200, 600},
        null, null, List.of());

    assertThat(PageGeometry.pixelRect(asset, 1000, 2000))
        .isEqualTo(new Rectangle(100, 400, 200, 600));
  }

  @Test
  @DisplayName("选项行过高时按宽度收窄")
  void limitsOptionRowHeightByWidth() {
    PageAsset asset = new PageAsset(
        "OPTION_ROW", "OPTIONS", null, new double[] {0.1, 0.1, 0.5, 1.0},
        "1x4", 4, List.of("A", "B", "C", "D"));

    assertThat(PageGeometry.pixelRect(asset, 1000, 2000))
        .isEqualTo(new Rectangle(100, 200, 500, 275));
  }

  @Test
  @DisplayName("越界或过小的资源区域视为无效")
  void rejectsInvalidAssetRect() {
    PageAsset asset = new PageAsset(
        "FIGURE", "STEM", null, new double[] {0.95, 0.95, 0.005, 0.005},
        null, null, List.of());

    assertThat(PageGeometry.pixelRect(asset, 1000, 2000)).isNull();
  }

  @Test
  @DisplayName("区域换算、并集与同列判断保持原有口径")
  void keepsRectUtilityBehavior() {
    Rectangle first = new Rectangle(10, 20, 30, 40);
    Rectangle second = new Rectangle(50, 20, 20, 40);

    assertThat(PageGeometry.normalizedRect(first, 100, 200))
        .containsExactly(0.1, 0.1, 0.3, 0.2);
    assertThat(PageGeometry.union(List.of(first, second)))
        .isEqualTo(new Rectangle(10, 20, 60, 40));
    assertThat(PageGeometry.inSameColumn(new Rectangle(40, 0, 10, 10), first)).isTrue();
    assertThat(PageGeometry.inSameColumn(new Rectangle(200, 0, 10, 10), first)).isFalse();
  }
}
