package interview.guide.modules.resume.document;

import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.log.PaperFlowLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本文档（TXT / Markdown / 旧版 DOC）：统一走 Tika 抽取正文，
 * 再按 40 行切成逻辑页，与 DOCX 一样直接进入结构切题，不调用视觉模型。
 */
@Component
@RequiredArgsConstructor
public class TextDocumentParser implements DocumentParser {

  private static final int LOGICAL_PAGE_LINES = 40;

  private final DocumentParseService documentParseService;

  @Override
  public boolean supports(String contentType, String filename) {
    boolean textMime = contentType != null && (contentType.startsWith("text/")
        || contentType.equalsIgnoreCase("application/msword"));
    if (filename == null) {
      return textMime;
    }
    String lower = filename.toLowerCase();
    return textMime || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".doc");
  }

  @Override
  public ParsedDocument parse(byte[] bytes, String filename, String contentType) {
    PaperFlowLog.event("TEXT_DOCUMENT_PARSE_BEGIN filename={}, contentType={}, bytes={}",
        filename, contentType, bytes == null ? 0 : bytes.length);
    String text;
    try {
      text = documentParseService.parseContent(bytes, filename);
    } catch (RuntimeException e) {
      PaperFlowLog.error("TEXT_DOCUMENT_PARSE_FAILED filename={}, contentType={}, bytes={}",
          e, filename, contentType, bytes == null ? 0 : bytes.length);
      throw e;
    }
    List<String> lines = splitLines(text);
    PaperFlowLog.event("TEXT_DOCUMENT_TEXT_PARSED filename={}, textChars={}, lines={}",
        filename, text == null ? 0 : text.length(), lines.size());

    List<ParsedPage> pages = new ArrayList<>();
    List<String> pageLines = new ArrayList<>();
    int pageNo = 1;
    for (String line : lines) {
      if (pageLines.size() >= LOGICAL_PAGE_LINES) {
        pages.add(new ParsedPage(pageNo++, PageSource.TEXT, pageLines, List.of(),
            null, true, null));
        pageLines = new ArrayList<>();
      }
      pageLines.add(line);
    }
    if (!pageLines.isEmpty() || pages.isEmpty()) {
      pages.add(new ParsedPage(pageNo, PageSource.TEXT, pageLines, List.of(),
          null, true, null));
    }
    PaperFlowLog.event("TEXT_DOCUMENT_PAGES_BUILT filename={}, pages={}, logicalPageLines={}",
        filename, pages.size(), LOGICAL_PAGE_LINES);
    return new ParsedDocument("TEXT", pages, List.of());
  }

  private List<String> splitLines(String text) {
    List<String> lines = new ArrayList<>();
    if (text == null) {
      return lines;
    }
    for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
      String trimmed = line.stripTrailing();
      if (!trimmed.isBlank()) {
        lines.add(trimmed);
      }
    }
    return lines;
  }
}
