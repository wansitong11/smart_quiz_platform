package interview.guide.modules.resume.document;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.service.PageImageNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * DOCX：用 POI 顺序解析段落与内嵌图片，文本/图片都有精确顺序，不走视觉模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocxDocumentParser implements DocumentParser {

  private static final int LOGICAL_PAGE_LINES = 40;

  private final PageImageNormalizer imageNormalizer;

  @Override
  public boolean supports(String contentType, String filename) {
    boolean docxMime = contentType != null
        && contentType.toLowerCase().contains("wordprocessingml");
    return docxMime || (filename != null && filename.toLowerCase().endsWith(".docx"));
  }

  @Override
  public ParsedDocument parse(byte[] bytes, String filename, String contentType) {
    List<String> warnings = new ArrayList<>();
    List<LineDraft> drafts = new ArrayList<>();
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
      int assetSeq = 0;
      for (IBodyElement element : document.getBodyElements()) {
        if (element instanceof XWPFParagraph paragraph) {
          assetSeq = readParagraph(paragraph, drafts, assetSeq);
        } else if (element instanceof XWPFTable table) {
          assetSeq = readTable(table, drafts, assetSeq);
        }
      }
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "无法打开 Word 文档，请确认文件未损坏");
    }

    List<ParsedPage> pages = new ArrayList<>();
    List<String> pageLines = new ArrayList<>();
    List<DocAsset> pageAssets = new ArrayList<>();
    int pageNo = 1;
    for (LineDraft draft : drafts) {
      if (pageLines.size() >= LOGICAL_PAGE_LINES) {
        pages.add(new ParsedPage(pageNo++, PageSource.TEXT, pageLines, pageAssets,
            null, true, null));
        pageLines = new ArrayList<>();
        pageAssets = new ArrayList<>();
      }
      String line = draft.text;
      pageLines.add(line);
      for (DocAsset asset : draft.assets) {
        pageAssets.add(asset);
      }
    }
    if (!pageLines.isEmpty() || pages.isEmpty()) {
      pages.add(new ParsedPage(pageNo, PageSource.TEXT, pageLines, pageAssets,
          null, true, null));
    }
    if (pages.size() == 1) {
      warnings.add("Word 文档未检测到分页符，按 40 行切分为逻辑页");
    }
    return new ParsedDocument("DOCX", pages, warnings);
  }

  private int readParagraph(XWPFParagraph paragraph, List<LineDraft> drafts, int assetSeq) {
    String text = paragraph.getText();
    List<DocAsset> images = new ArrayList<>();
    assetSeq = readPictures(paragraph, images, assetSeq);
    if (text == null || text.isBlank()) {
      if (!images.isEmpty()) {
        drafts.add(new LineDraft("", images));
      }
      return assetSeq;
    }
    String[] parts = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
    for (int i = 0; i < parts.length; i++) {
      String line = parts[i].stripTrailing();
      if (line.isBlank()) {
        continue;
      }
      drafts.add(new LineDraft(line, i == 0 ? images : List.of()));
    }
    return assetSeq;
  }

  private int readTable(XWPFTable table, List<LineDraft> drafts, int assetSeq) {
    for (XWPFTableRow row : table.getRows()) {
      List<String> cells = new ArrayList<>();
      List<DocAsset> images = new ArrayList<>();
      for (XWPFTableCell cell : row.getTableCells()) {
        cells.add(cell.getText() == null ? "" : cell.getText().replace("\n", " ").trim());
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
          assetSeq = readPictures(paragraph, images, assetSeq);
        }
      }
      String line = String.join(" | ", cells).trim();
      if (!line.isBlank() || !images.isEmpty()) {
        drafts.add(new LineDraft(line, images));
      }
    }
    return assetSeq;
  }

  private int readPictures(XWPFParagraph paragraph, List<DocAsset> images, int assetSeq) {
    List<byte[]> pictures = new ArrayList<>();
    for (XWPFRun run : paragraph.getRuns()) {
      for (XWPFPicture picture : run.getEmbeddedPictures()) {
        try {
          byte[] data = picture.getPictureData().getData();
          if (data != null && data.length > 0) {
            pictures.add(data);
          }
        } catch (Exception e) {
          log.warn("DOCX 图片读取失败: {}", e.getMessage());
        }
      }
    }
    if (pictures.isEmpty()) {
      return assetSeq;
    }
    String anchor = paragraph.getText();
    // DOCX 的图片本来就是一张一张独立的：同段多张按选项项处理，由后端按顺序挂到 A/B/C/D
    boolean optionItems = pictures.size() >= 2;
    for (byte[] data : pictures) {
      byte[] jpeg = normalizeQuietly(data);
      String key = "a" + (++assetSeq);
      images.add(optionItems
          ? new DocAsset(key, AssetKind.OPTION_ITEM, AssetRole.OPTIONS, jpeg, anchor,
              null, null, null, null, 0, 0)
          : DocAsset.figure(key, jpeg, anchor, 0, 0));
    }
    return assetSeq;
  }

  private byte[] normalizeQuietly(byte[] data) {
    try {
      return imageNormalizer.normalizeUploadedImage(data);
    } catch (Exception e) {
      return data;
    }
  }

  private record LineDraft(String text, List<DocAsset> assets) {
  }
}
