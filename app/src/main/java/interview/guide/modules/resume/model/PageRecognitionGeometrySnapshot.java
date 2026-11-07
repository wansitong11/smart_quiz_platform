package interview.guide.modules.resume.model;

import java.util.List;

/**
 * 一页识别结果的几何快照。
 *
 * <p>识别阶段的 lines 与最终资源 rect 一旦落库，切题阶段就不再依赖模型重新猜锚点。
 * 快照写入 {@code paper_pages.assets_json}，资源文件本身仍在 {@code paper_assets}。</p>
 */
public record PageRecognitionGeometrySnapshot(
    int version,
    int pageNo,
    List<PageRecognition.PageLine> lines,
    List<PageAssetGeometry> assets
) {

  public static final int CURRENT_VERSION = 1;

  public PageRecognitionGeometrySnapshot {
    lines = lines == null ? List.of() : List.copyOf(lines);
    assets = assets == null ? List.of() : List.copyOf(assets);
  }

  public static PageRecognitionGeometrySnapshot empty(int pageNo) {
    return new PageRecognitionGeometrySnapshot(CURRENT_VERSION, pageNo, List.of(), List.of());
  }

  public boolean compatible() {
    return version == CURRENT_VERSION;
  }
}
