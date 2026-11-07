package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.modules.resume.document.AssetKind;
import interview.guide.modules.resume.document.AssetRole;
import interview.guide.modules.resume.document.DocAsset;
import interview.guide.modules.resume.document.DocxDocumentParser;
import interview.guide.modules.resume.document.ImageDocumentParser;
import interview.guide.modules.resume.document.ParsedDocument;
import interview.guide.modules.resume.document.ParsedPage;
import interview.guide.modules.resume.document.PdfDocumentParser;
import interview.guide.modules.resume.model.PageSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库正文抽取测试")
class KnowledgeBaseContentExtractorTest {

  @Mock
  private ImageDocumentParser imageDocumentParser;
  @Mock
  private PdfDocumentParser pdfDocumentParser;
  @Mock
  private DocxDocumentParser docxDocumentParser;
  @Mock
  private DocumentParseService documentParseService;
  @Mock
  private KnowledgeBaseImageOcrService ocrService;

  private KnowledgeBaseOcrProperties properties;
  private KnowledgeBaseContentExtractor extractor;

  @BeforeEach
  void setUp() {
    properties = new KnowledgeBaseOcrProperties();
    extractor = new KnowledgeBaseContentExtractor(
        imageDocumentParser, pdfDocumentParser, docxDocumentParser,
        documentParseService, ocrService, properties);
  }

  @Test
  @DisplayName("图片文件调用一次识图")
  void shouldOcrImageFile() {
    ParsedDocument doc = new ParsedDocument("IMAGE",
        List.of(imagePage(1)), List.of());
    when(imageDocumentParser.parse(any(byte[].class), eq("a.jpg"), isNull()))
        .thenReturn(doc);
    when(ocrService.ocrPageImage(any(byte[].class), eq(1), eq(1)))
        .thenReturn("图片文字");

    String text = extractor.extractText(new byte[] {1}, "a.jpg");

    assertThat(text).isEqualTo("图片文字");
    verify(ocrService).ocrPageImage(any(byte[].class), eq(1), eq(1));
  }

  @Test
  @DisplayName("混合 PDF 只识图扫描页")
  void shouldOcrOnlyImagePagesInMixedPdf() {
    ParsedDocument doc = new ParsedDocument("PDF_MIXED",
        List.of(textPage(1, "第一页"), imagePage(2)), List.of());
    when(pdfDocumentParser.parse(any(byte[].class), eq("a.pdf"), eq("application/pdf")))
        .thenReturn(doc);
    when(ocrService.ocrPageImage(any(byte[].class), eq(2), eq(2)))
        .thenReturn("扫描页文字");

    String text = extractor.extractText(new byte[] {1}, "a.pdf");

    assertThat(text).isEqualTo("第一页\n扫描页文字");
    verify(ocrService, times(1))
        .ocrPageImage(any(byte[].class), anyInt(), anyInt());
  }

  @Test
  @DisplayName("纯文字 PDF 不调用识图")
  void shouldNotOcrTextOnlyPdf() {
    ParsedDocument doc = new ParsedDocument("PDF_TEXT",
        List.of(textPage(1, "第一页"), textPage(2, "第二页")), List.of());
    when(pdfDocumentParser.parse(any(byte[].class), eq("a.pdf"), eq("application/pdf")))
        .thenReturn(doc);

    String text = extractor.extractText(new byte[] {1}, "a.pdf");

    assertThat(text).isEqualTo("第一页\n第二页");
    verifyNoInteractions(ocrService);
  }

  @Test
  @DisplayName("PDF 识图页数超过上限时失败")
  void shouldRejectPdfOverPageLimit() {
    properties.setMaxPages(1);
    ParsedDocument doc = new ParsedDocument("PDF_SCAN",
        List.of(imagePage(1), imagePage(2)), List.of());
    when(pdfDocumentParser.parse(any(byte[].class), eq("a.pdf"), eq("application/pdf")))
        .thenReturn(doc);
    when(ocrService.ocrPageImage(any(byte[].class), eq(1), eq(2)))
        .thenReturn("第一页");

    assertThatThrownBy(() -> extractor.extractText(new byte[] {1}, "a.pdf"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("识别页数超过上限");
    verify(ocrService, times(1))
        .ocrPageImage(any(byte[].class), anyInt(), anyInt());
  }

  @Test
  @DisplayName("DOCX 内嵌图按锚点顺序插回正文")
  void shouldInsertDocxImageTextAfterAnchor() {
    DocAsset first = docxAsset("a1", new byte[] {1}, 0);
    DocAsset second = docxAsset("a2", new byte[] {2}, 0);
    ParsedDocument doc = new ParsedDocument("DOCX",
        List.of(new ParsedPage(1, PageSource.TEXT, List.of("正文"),
            List.of(first, second), null, true, null)), List.of());
    when(docxDocumentParser.parse(any(byte[].class), eq("a.docx"), anyString()))
        .thenReturn(doc);
    when(ocrService.ocrDocxImage(any(byte[].class), eq(1))).thenReturn("图一");
    when(ocrService.ocrDocxImage(any(byte[].class), eq(2))).thenReturn("图二");

    String text = extractor.extractText(new byte[] {1}, "a.docx");

    assertThat(text).isEqualTo("正文\n图一\n图二");
  }

  @Test
  @DisplayName("DOCX 装饰小图不调用识图")
  void shouldSkipTinyDocxImage() throws IOException {
    DocAsset icon = docxAsset("icon", imageBytes(32, 32), 0);
    ParsedDocument doc = new ParsedDocument("DOCX",
        List.of(new ParsedPage(1, PageSource.TEXT, List.of("正文"),
            List.of(icon), null, true, null)), List.of());
    when(docxDocumentParser.parse(any(byte[].class), eq("a.docx"), anyString()))
        .thenReturn(doc);

    String text = extractor.extractText(new byte[] {1}, "a.docx");

    assertThat(text).isEqualTo("正文");
    verify(ocrService, never()).ocrDocxImage(any(byte[].class), anyInt());
  }

  @Test
  @DisplayName("DOCX 内嵌图数量超过上限时失败")
  void shouldRejectDocxOverImageLimit() {
    properties.setMaxDocxImages(1);
    ParsedDocument doc = new ParsedDocument("DOCX",
        List.of(new ParsedPage(1, PageSource.TEXT, List.of("正文"),
            List.of(docxAsset("a1", new byte[] {1}, 0),
                docxAsset("a2", new byte[] {2}, 0)), null, true, null)), List.of());
    when(docxDocumentParser.parse(any(byte[].class), eq("a.docx"), anyString()))
        .thenReturn(doc);
    when(ocrService.ocrDocxImage(any(byte[].class), eq(1))).thenReturn("图一");

    assertThatThrownBy(() -> extractor.extractText(new byte[] {1}, "a.docx"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("文档内图片数量超过上限");
  }

  @Test
  @DisplayName("识图关闭时回退通用文本解析")
  void shouldFallbackToDocumentParseWhenOcrDisabled() {
    properties.setEnabled(false);
    when(documentParseService.parseContent(any(byte[].class), eq("a.pdf")))
        .thenReturn("Tika 文本");

    String text = extractor.extractText(new byte[] {1}, "a.pdf");

    assertThat(text).isEqualTo("Tika 文本");
    verifyNoInteractions(pdfDocumentParser, ocrService);
  }

  @Test
  @DisplayName("TXT / MD / DOC 继续走通用文本解析")
  void shouldUseDocumentParseForPlainText() {
    when(documentParseService.parseContent(any(byte[].class), eq("a.txt")))
        .thenReturn("纯文本");

    String text = extractor.extractText(new byte[] {1}, "a.txt");

    assertThat(text).isEqualTo("纯文本");
    verifyNoInteractions(imageDocumentParser, pdfDocumentParser, docxDocumentParser, ocrService);
  }

  private ParsedPage textPage(int pageNo, String text) {
    return new ParsedPage(pageNo, PageSource.TEXT, List.of(text),
        List.of(), null, false, null);
  }

  private ParsedPage imagePage(int pageNo) {
    return new ParsedPage(pageNo, PageSource.IMAGE, List.of(),
        List.of(), new byte[] {1, 2}, false, null);
  }

  private DocAsset docxAsset(String key, byte[] bytes, int anchorLineIndex) {
    return new DocAsset(key, AssetKind.FIGURE, AssetRole.STEM, bytes,
        "正文", null, null, null, null, 0, 0, anchorLineIndex);
  }

  private byte[] imageBytes(int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }
}
