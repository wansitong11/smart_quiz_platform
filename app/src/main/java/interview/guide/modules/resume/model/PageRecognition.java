package interview.guide.modules.resume.model;

import java.util.List;

/**
 * 视觉模型对单页的结构化输出：只负责"看清"，不负责切题。
 *
 * @param pageNo    页码
 * @param text      保留版式的转写文本（题号 / 选项 / 分值 / 标题）
 * @param assets    页面资源（图形选项、题干图、表格）
 * @param hasFigure 本页是否含图（几何图、表格、插图）
 * @param notes     识别异常说明
 */
public record PageRecognition(
    int pageNo,
    String text,
    List<PageAsset> assets,
    boolean hasFigure,
    String notes
) {

  /**
   * @param kind        FIGURE / OPTION_ROW / TABLE
   * @param role        STEM / OPTIONS / MATERIAL
   * @param anchorText  该资源对应的文本行（用于挂到结构节点）
   * @param bbox        归一化坐标 [x,y,w,h]
   * @param layout      1x4 / 2x2
   * @param optionCount 选项数
   * @param labels      选项字母
   */
  public record PageAsset(
      String kind,
      String role,
      String anchorText,
      double[] bbox,
      String layout,
      Integer optionCount,
      List<String> labels
  ) {
  }
}
