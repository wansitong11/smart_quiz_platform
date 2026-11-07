package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognition.PageAsset;
import interview.guide.modules.resume.model.PageRecognitionDiagnostic;
import interview.guide.modules.resume.model.StemFigureVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("题干图声明与最终资源一致性")
class PaperStemFigureConsistencyTest {

  @Test
  @DisplayName("声明两个题干图但只返回一个 FIGURE/STEM 时逐项判定缺失")
  void shouldMarkMissingAnchorWhenFigureWasNotReturned() {
    String paperCut = "8 马年剪纸，观察后回答问题";
    String photo = "9 小记者运动会照片，读图完成练习";
    PageAsset stem = figureStem(paperCut);

    Map<String, StemFigureVerdict> verdicts =
        PaperStemFigureConsistency.check(List.of(paperCut, photo), List.of(stem));

    assertThat(verdicts)
        .containsEntry(paperCut, StemFigureVerdict.MATCHED)
        .containsEntry(photo, StemFigureVerdict.MISSING);
    assertThat(PaperStemFigureConsistency.hasMissing(List.of(paperCut, photo), List.of(stem)))
        .isTrue();
  }

  @Test
  @DisplayName("题号与标点写法不同也能唯一对齐")
  void shouldMatchWhenQuestionNumberAndPunctuationDiffer() {
    String declared = "8 马年剪纸，观察后回答问题";
    String returned = "第8题 马年剪纸 观察后回答问题";

    assertThat(PaperStemFigureConsistency.check(
        List.of(declared), List.of(figureStem(returned))))
        .containsEntry(declared, StemFigureVerdict.MATCHED);
  }

  @Test
  @DisplayName("多个不同资源锚点同时命中一个声明时判定为无法唯一对齐")
  void shouldMarkAmbiguousWhenMultipleAssetsMatch() {
    String declared = "9 小记者运动会照片";
    PageAsset first = figureStem("9 小记者运动会照片");
    PageAsset second = figureStem("9 小记者运动会照片（续）");

    assertThat(PaperStemFigureConsistency.check(List.of(declared), List.of(first, second)))
        .containsEntry(declared, StemFigureVerdict.AMBIGUOUS);
  }

  @Test
  @DisplayName("无声明锚点时视为一致，不触发失败")
  void shouldPassWhenNoAnchorDeclared() {
    assertThat(PaperStemFigureConsistency.hasMissing(List.of(), List.of())).isFalse();
  }

  @Test
  @DisplayName("失败文案点名具体缺失锚点")
  void shouldNameMissingAnchorInFailureMessage() {
    String missing = "9 小记者运动会照片，读图完成练习";
    Map<String, StemFigureVerdict> verdicts =
        PaperStemFigureConsistency.check(List.of(missing), List.of());

    String message = PaperStemFigureConsistency.failureMessage(2, verdicts);

    assertThat(message)
        .startsWith("第 2 页题干图缺失：")
        .contains("9 小记者运动会照片");
  }

  @Test
  @DisplayName("诊断快照只保存锚点摘要与逐项结论")
  void shouldBuildBoundedDiagnostic() {
    String missing = "9 小记者运动会照片，读图完成练习";
    Map<String, StemFigureVerdict> verdicts =
        PaperStemFigureConsistency.check(List.of(missing), List.of());

    PageRecognitionDiagnostic diagnostic = PaperStemFigureConsistency.diagnostic(
        "INCOMPLETE_CONTENT", 2, List.of(missing), List.of(), verdicts);

    assertThat(diagnostic.failureClass()).isEqualTo("INCOMPLETE_CONTENT");
    assertThat(diagnostic.declaredAnchors()).containsExactly(missing);
    assertThat(diagnostic.resolvedStemAnchors()).isEmpty();
    assertThat(diagnostic.verdicts()).containsEntry(missing, StemFigureVerdict.MISSING);
  }

  private PageAsset figureStem(String anchorText) {
    return new PageAsset(
        "FIGURE", "STEM", anchorText,
        new double[] {0.05, 0.20, 0.30, 0.25}, null, null, List.of());
  }
}
