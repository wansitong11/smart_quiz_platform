package interview.guide.modules.resume.model;

import java.util.List;
import java.util.Map;

/**
 * 识别失败诊断快照，序列化到 paper_pages.diagnostic_json。
 *
 * <p>只保存结构化字段与锚点摘要；不保存模型原始输出、图片字节或上游原始 message。</p>
 *
 * @param failureClass        视觉失败类别名
 * @param pageNo              页码
 * @param declaredAnchors     模型声明的题干图锚点摘要
 * @param resolvedStemAnchors 最终 FIGURE/STEM 资源的锚点摘要
 * @param verdicts            声明锚点 -> 逐项结论名
 */
public record PageRecognitionDiagnostic(
    String failureClass,
    int pageNo,
    List<String> declaredAnchors,
    List<String> resolvedStemAnchors,
    Map<String, StemFigureVerdict> verdicts
) {
}
