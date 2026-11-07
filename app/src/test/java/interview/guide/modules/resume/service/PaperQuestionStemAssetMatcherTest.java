package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PaperAssetEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("题目题干图确定性挂接")
class PaperQuestionStemAssetMatcherTest {

  private final PaperQuestionStemAssetMatcher matcher = new PaperQuestionStemAssetMatcher();

  @Test
  @DisplayName("模型漏写题干图 assetRef 时按题号范围和 STEM 角色补挂")
  void shouldAttachUnreferencedStemFigureWithinQuestionRange() {
    List<PaperAssetEntity> assets = List.of(
        asset("p1a2", "OPTION_ROW", "OPTIONS", 2),
        asset("p1a2-stem", "FIGURE", "STEM", 2),
        asset("p1a3-stem", "FIGURE", "STEM", 3)
    );

    List<String> result = matcher.resolve(
        "2. 如图，正方体的三个侧面分别画有不同图案，它的平面展开图可以是",
        List.of("p1a2"),
        assets,
        2,
        4
    );

    assertThat(result).containsExactly("p1a2-stem");
  }

  @Test
  @DisplayName("普通选项题不补挂题干图")
  void shouldNotAttachStemWithoutVisualReference() {
    List<PaperAssetEntity> assets = List.of(
        asset("p1a1", "OPTION_ROW", "OPTIONS", 1),
        asset("p1a1-stem", "FIGURE", "STEM", 1)
    );

    List<String> result = matcher.resolve(
        "1. 下列是正方体展开图的是",
        List.of("p1a1"),
        assets,
        1,
        3
    );

    assertThat(result).isEmpty();
  }

  private PaperAssetEntity asset(String key, String kind, String role, int anchorLine) {
    PaperAssetEntity asset = new PaperAssetEntity();
    asset.setAssetKey(key);
    asset.setKind(kind);
    asset.setRole(role);
    asset.setAnchorLine(anchorLine);
    return asset;
  }
}
