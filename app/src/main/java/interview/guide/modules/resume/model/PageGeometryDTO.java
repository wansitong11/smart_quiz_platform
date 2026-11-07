package interview.guide.modules.resume.model;

import interview.guide.modules.resume.model.PageRecognition.PageAsset;
import interview.guide.modules.resume.model.PageRecognition.PageLine;

import java.util.List;

/**
 * 两阶段识别的资源几何阶段结果：只返回资源及其归属所需的行坐标。
 */
public record PageGeometryDTO(
    List<PageLine> lines,
    List<PageAsset> assets,
    List<String> stemFigureAnchors,
    String notes
) {
}
