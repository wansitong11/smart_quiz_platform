package interview.guide.modules.resume.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 单阶段视觉识别的模型输出协议：只要求行级 {@code lines}，不重复输出整页 {@code text}。
 *
 * <p>整页文本由后端从 {@code lines} 派生，避免同一份全文在 JSON 中出现两次，
 * 减少输出 token、降低响应截断概率。</p>
 */
public record PageRecognitionOutput(
    int pageNo,
    List<PageRecognition.PageLine> lines,
    List<PageRecognition.PageAsset> assets,
    boolean hasFigure,
    List<String> stemFigureAnchors,
    String notes
) {

  /**
   * 转成领域对象，整页 {@code text} 从 {@code lines} 派生。
   */
  public PageRecognition toPageRecognition() {
    String text = lines == null ? "" : lines.stream()
        .map(PageRecognition.PageLine::text)
        .filter(line -> line != null && !line.isBlank())
        .map(String::strip)
        .collect(Collectors.joining("\n"));
    return new PageRecognition(
        pageNo, text, lines, assets, hasFigure, stemFigureAnchors, notes);
  }
}
