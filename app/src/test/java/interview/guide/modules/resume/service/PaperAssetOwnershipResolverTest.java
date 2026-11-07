package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageAssetGeometry;
import interview.guide.modules.resume.model.PageRecognition;
import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("试卷资源锚点确定性消歧")
class PaperAssetOwnershipResolverTest {

  private final PaperAssetOwnershipResolver resolver = new PaperAssetOwnershipResolver();

  @Test
  @DisplayName("相同 A. 锚点按纵向距离分别归属相邻题")
  void resolvesAdjacentAnchorsByVerticalDistance() {
    List<PageRecognition.PageLine> lines = pageLines();
    PageRecognitionGeometrySnapshot snapshot = snapshot(List.of(
        asset("p1a3", "TABLE", "OPTIONS", "A.", new double[] {0.12, 0.625, 0.78, 0.123}),
        asset("p1a4", "OPTION_ROW", "OPTIONS", "A.", new double[] {0.117, 0.746, 0.883, 0.117})
    ), lines);

    Map<String, PaperAssetBinding> bindings = resolver.resolve(snapshot, 1);

    assertThat(bindings.get("p1a3").anchorLine()).isEqualTo(16);
    assertThat(bindings.get("p1a4").anchorLine()).isEqualTo(21);
  }

  @Test
  @DisplayName("双栏页面只选择资源所在栏")
  void prefersSameColumn() {
    PageRecognitionGeometrySnapshot snapshot = snapshot(List.of(
        asset("p1a1", "OPTION_ROW", "OPTIONS", "A.",
            new double[] {0.56, 0.30, 0.38, 0.10})
    ), List.of(
        new PageRecognition.PageLine("A. 左侧选项", new double[] {0.08, 0.31, 0.38, 0.03}),
        new PageRecognition.PageLine("A. 右侧选项", new double[] {0.56, 0.31, 0.38, 0.03})
    ));

    Map<String, PaperAssetBinding> bindings = resolver.resolve(snapshot, 1);

    assertThat(bindings.get("p1a1").anchorLine()).isEqualTo(2);
  }

  @Test
  @DisplayName("候选分数相同则保持未绑定，不随机选择")
  void keepsTieUnbound() {
    PageRecognitionGeometrySnapshot snapshot = snapshot(List.of(
        asset("p1a1", "OPTION_ROW", "OPTIONS", "A.",
            new double[] {0.10, 0.30, 0.80, 0.10})
    ), List.of(
        new PageRecognition.PageLine("A. 选项一", new double[] {0.10, 0.31, 0.80, 0.03}),
        new PageRecognition.PageLine("A. 选项二", new double[] {0.10, 0.31, 0.80, 0.03})
    ));

    assertThat(resolver.resolve(snapshot, 1)).isEmpty();
  }

  private PageRecognitionGeometrySnapshot snapshot(
      List<PageAssetGeometry> assets,
      List<PageRecognition.PageLine> lines
  ) {
    return new PageRecognitionGeometrySnapshot(
        PageRecognitionGeometrySnapshot.CURRENT_VERSION, 1, lines, assets);
  }

  private PageAssetGeometry asset(
      String key,
      String kind,
      String role,
      String anchor,
      double[] rect
  ) {
    return new PageAssetGeometry(key, kind, role, anchor, rect, "1x4", 4);
  }

  private List<PageRecognition.PageLine> pageLines() {
    List<PageRecognition.PageLine> lines = new ArrayList<>();
    for (int i = 1; i <= 21; i++) {
      String text = i == 16 || i == 21 ? "A. [图片]" : "第 " + i + " 行";
      double y = i == 16 ? 0.635 : i == 21 ? 0.79 : 0.02 + i * 0.035;
      lines.add(new PageRecognition.PageLine(
          text, new double[] {0.10, y, 0.80, 0.03}));
    }
    return lines;
  }
}
