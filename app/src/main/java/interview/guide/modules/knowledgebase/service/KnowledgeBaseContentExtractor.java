package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.modules.resume.document.DocAsset;
import interview.guide.modules.resume.document.DocxDocumentParser;
import interview.guide.modules.resume.document.ImageDocumentParser;
import interview.guide.modules.resume.document.ParsedDocument;
import interview.guide.modules.resume.document.ParsedPage;
import interview.guide.modules.resume.document.PdfDocumentParser;
import interview.guide.modules.resume.model.PageSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库正文抽取：按文件类型路由到图片、PDF、DOCX 或 Tika 文本解析。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseContentExtractor {

  private static final int MIN_DOCX_IMAGE_MAX_SIDE = 64;

  private final ImageDocumentParser imageDocumentParser;
  private final PdfDocumentParser pdfDocumentParser;
  private final DocxDocumentParser docxDocumentParser;
  private final DocumentParseService documentParseService;
  private final KnowledgeBaseImageOcrService ocrService;
  private final KnowledgeBaseOcrProperties ocrProperties;

  /**
   * 唯一正文抽取入口；bytes 为 RustFS 下载到的原始字节。
   */
  public String extractText(byte[] bytes, String filename) {
    if (!ocrProperties.isEnabled()) {
      return documentParseService.parseContent(bytes, filename);
    }
    String ext = extension(filename);
    if ("jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)) {
      return extractImage(bytes, filename);
    }
    if ("pdf".equals(ext)) {
      return extractPdf(bytes, filename);
    }
    if ("docx".equals(ext)) {
      return extractDocx(bytes, filename);
    }
    return documentParseService.parseContent(bytes, filename);
  }

  private String extractImage(byte[] bytes, String filename) {
    ParsedDocument doc = imageDocumentParser.parse(bytes, filename, null);
    if (doc.pages().isEmpty()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
          "图片解析失败，请确认文件未损坏");
    }
    enforceMaxPages(1);
    ParsedPage page = doc.pages().get(0);
    return ocrService.ocrPageImage(page.pageImage(), 1, 1);
  }

  private String extractPdf(byte[] bytes, String filename) {
    ParsedDocument doc = pdfDocumentParser.parse(bytes, filename, "application/pdf");
    StringBuilder sb = new StringBuilder();
    int visionPages = 0;
    for (ParsedPage page : doc.pages()) {
      if (page.source() == PageSource.TEXT) {
        appendLines(sb, page.lines());
      } else {
        visionPages++;
        enforceMaxPages(visionPages);
        appendLine(sb, ocrService.ocrPageImage(
            page.pageImage(), page.pageNo(), doc.pages().size()));
      }
    }
    return sb.toString().strip();
  }

  private String extractDocx(byte[] bytes, String filename) {
    ParsedDocument doc = docxDocumentParser.parse(bytes, filename,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    StringBuilder sb = new StringBuilder();
    int ocrImages = 0;
    for (ParsedPage page : doc.pages()) {
      List<String> lines = new ArrayList<>(page.lines());
      Map<Integer, List<String>> ocrByAnchor = new LinkedHashMap<>();
      List<String> tail = new ArrayList<>();
      if (page.assets() != null) {
        for (DocAsset asset : page.assets()) {
          if (asset.bytes() == null || asset.bytes().length == 0) {
            continue;
          }
          if (isTooSmallDocxImage(asset)) {
            continue;
          }
          ocrImages++;
          enforceMaxDocxImages(ocrImages);
          String text = ocrService.ocrDocxImage(asset.bytes(), ocrImages);
          Integer anchor = asset.anchorLineIndex();
          if (anchor != null && anchor >= 0 && anchor < lines.size()) {
            ocrByAnchor.computeIfAbsent(anchor, key -> new ArrayList<>()).add(text);
          } else {
            tail.add(text);
          }
        }
      }
      for (int i = 0; i < lines.size(); i++) {
        appendLine(sb, lines.get(i));
        for (String text : ocrByAnchor.getOrDefault(i, List.of())) {
          appendLine(sb, text);
        }
      }
      for (String text : tail) {
        appendLine(sb, text);
      }
    }
    return sb.toString().strip();
  }

  private void enforceMaxPages(int used) {
    if (used > ocrProperties.getMaxPages()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
          "识别页数超过上限（" + ocrProperties.getMaxPages() + " 页），请拆分后重新上传");
    }
  }

  private void enforceMaxDocxImages(int used) {
    if (used > ocrProperties.getMaxDocxImages()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
          "文档内图片数量超过上限（" + ocrProperties.getMaxDocxImages() + " 张）");
    }
  }

  private void appendLines(StringBuilder sb, List<String> lines) {
    for (String line : lines) {
      appendLine(sb, line);
    }
  }

  private void appendLine(StringBuilder sb, String line) {
    if (line != null && !line.isBlank()) {
      sb.append(line.strip()).append('\n');
    }
  }

  /**
   * DOCX 中的图标、分隔线等装饰图不值得单独调用视觉模型。
   * 无法解码的格式保持原行为，交给后续 OCR 或失败处理，不能静默丢弃。
   */
  private boolean isTooSmallDocxImage(DocAsset asset) {
    if (asset.width() != null && asset.width() > 0
        && asset.height() != null && asset.height() > 0) {
      return Math.max(asset.width(), asset.height()) < MIN_DOCX_IMAGE_MAX_SIDE;
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(asset.bytes()));
      return image != null
          && Math.max(image.getWidth(), image.getHeight()) < MIN_DOCX_IMAGE_MAX_SIDE;
    } catch (IOException e) {
      return false;
    }
  }

  private String extension(String filename) {
    if (filename == null) {
      return "";
    }
    int i = filename.lastIndexOf('.');
    return i < 0 ? "" : filename.substring(i + 1).toLowerCase(Locale.ROOT);
  }
}
