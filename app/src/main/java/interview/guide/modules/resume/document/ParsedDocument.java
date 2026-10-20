package interview.guide.modules.resume.document;

import java.util.List;

/**
 * 解析结果。sourceType: PDF_TEXT / PDF_SCAN / IMAGE / DOCX / DOC。
 */
public record ParsedDocument(
    String sourceType,
    List<ParsedPage> pages,
    List<String> warnings
) {
}
