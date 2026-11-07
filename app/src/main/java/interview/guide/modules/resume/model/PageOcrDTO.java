package interview.guide.modules.resume.model;

import java.util.List;

/**
 * 两阶段识别的 OCR 阶段结果：只负责文字和视觉内容意图，不返回坐标。
 */
public record PageOcrDTO(
    int pageNo,
    String text,
    boolean hasFigure,
    List<String> stemFigureAnchors,
    String notes
) {
}
