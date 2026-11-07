package interview.guide.modules.resume.service;

import interview.guide.modules.resume.document.AssetKind;
import interview.guide.modules.resume.document.AssetRole;
import interview.guide.modules.resume.document.DocAsset;
import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import interview.guide.modules.resume.model.PaperAssetEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TEXT 页文本资源几何")
class TextPageGeometryTest {

  private final PaperAssetOwnershipResolver resolver = new PaperAssetOwnershipResolver();

  @Test
  @DisplayName("DOCX 相同选项文本按解析行号生成可区分的合成坐标")
  void shouldUseParserLineIndexForDocxAssets() {
    List<String> lines = List.of(
        "3. 看字选音",
        "A. [图片] B. [图片] C. [图片] D. [图片]",
        "4. 看图选字",
        "A. [图片] B. [图片] C. [图片] D. [图片]"
    );
    List<DocAsset> assets = List.of(
        docAsset("p1a3", AssetKind.TABLE, 1),
        docAsset("p1a4", AssetKind.OPTION_ROW, 3)
    );

    PageRecognitionGeometrySnapshot snapshot =
        TextPageGeometry.fromParsedPage(1, lines, assets);
    Map<String, PaperAssetBinding> bindings = resolver.resolve(snapshot, 1);

    assertThat(bindings.get("p1a3").anchorLine()).isEqualTo(2);
    assertThat(bindings.get("p1a4").anchorLine()).isEqualTo(4);
  }

  @Test
  @DisplayName("历史 TEXT 页已有真实 rect 时保持原坐标")
  void shouldRestoreStoredTextRect() {
    PaperAssetEntity asset = new PaperAssetEntity();
    asset.setAssetKey("p1a4");
    asset.setKind("OPTION_ROW");
    asset.setRole("OPTIONS");
    asset.setAnchorText("A.");
    asset.setRectJson("[0.1,0.73,0.8,0.06]");

    PageRecognitionGeometrySnapshot snapshot = TextPageGeometry.fromStoredPage(
        1,
        List.of(asset),
        List.of("3. 第一题", "A. [图片]", "4. 第二题", "A. [图片]")
    );

    assertThat(resolver.resolve(snapshot, 1).get("p1a4").anchorLine()).isEqualTo(4);
  }

  private DocAsset docAsset(String key, AssetKind kind, int anchorLineIndex) {
    return new DocAsset(
        key,
        kind,
        AssetRole.OPTIONS,
        new byte[0],
        "A.",
        kind == AssetKind.TABLE ? "2x2" : "1x4",
        4,
        List.of(),
        null,
        0,
        0,
        anchorLineIndex
    );
  }
}
