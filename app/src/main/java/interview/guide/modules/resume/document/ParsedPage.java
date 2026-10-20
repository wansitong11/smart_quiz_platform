package interview.guide.modules.resume.document;

import interview.guide.modules.resume.model.PageSource;

import java.util.List;

/**
 * 解析后的一页：文本行 + 资源 + 页图（DOCX 为空）。
 */
public record ParsedPage(
    int pageNo,
    PageSource source,
    List<String> lines,
    List<DocAsset> assets,
    byte[] pageImage,
    boolean logicalPage,
    String note
) {
}
