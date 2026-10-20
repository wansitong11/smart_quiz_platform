package interview.guide.modules.resume.document;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.service.PageImageNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF：逐页抽取文本行 + 图片资源；文字层不可信的页标成 IMAGE，交给视觉模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfDocumentParser implements DocumentParser {

  private static final float RENDER_DPI = 180f;

  private final PageImageNormalizer imageNormalizer;

  @Override
  public boolean supports(String contentType, String filename) {
    return (contentType != null && contentType.toLowerCase().contains("pdf"))
        || (filename != null && filename.toLowerCase().endsWith(".pdf"));
  }

  @Override
  public ParsedDocument parse(byte[] bytes, String filename, String contentType) {
    List<ParsedPage> pages = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    boolean anyText = false;
    boolean anyScan = false;
    try (PDDocument document = PDDocument.load(bytes)) {
      PDFRenderer renderer = new PDFRenderer(document);
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      int assetSeq = 0;
      for (int pageNo = 1; pageNo <= document.getNumberOfPages(); pageNo++) {
        PDPage page = document.getPage(pageNo - 1);
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        String rawText = stripper.getText(document);
        List<String> lines = splitLines(rawText);

        List<PlacedImage> images = collectImages(page);
        double coverage = imageCoverage(images, page);
        PageSource source = PageSourceDecider.decide(rawText, coverage);
        byte[] pageImage = renderPage(renderer, pageNo);

        List<DocAsset> assets = new ArrayList<>();
        if (source == PageSource.TEXT) {
          anyText = true;
          for (PlacedImage placed : images) {
            DocAsset asset = toAsset(placed, page, lines, "a" + (++assetSeq));
            if (asset != null) {
              assets.add(asset);
            }
          }
        } else {
          anyScan = true;
          lines = List.of();
        }
        pages.add(new ParsedPage(pageNo, source, lines, assets, pageImage, false, null));
      }
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "无法打开 PDF，请确认文件未损坏");
    }
    String sourceType = anyScan ? "PDF_SCAN" : "PDF_TEXT";
    if (anyText && anyScan) {
      sourceType = "PDF_MIXED";
    }
    if (anyScan && anyText) {
      warnings.add("检测到混合 PDF：文字页直接使用文字层，扫描页走视觉识别");
    }
    return new ParsedDocument(sourceType, pages, warnings);
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

  private byte[] renderPage(PDFRenderer renderer, int pageNo) {
    try {
      BufferedImage image = renderer.renderImageWithDPI(pageNo - 1, RENDER_DPI);
      try {
        return imageNormalizer.toJpeg(image);
      } finally {
        image.flush();
      }
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "PDF 第 " + pageNo + " 页渲染失败");
    }
  }

  private List<PlacedImage> collectImages(PDPage page) {
    ImageCollector collector = new ImageCollector();
    try {
      collector.processPage(page);
      return collector.images;
    } catch (IOException e) {
      log.warn("PDF 图片定位失败，按无图处理: {}", e.getMessage());
      return List.of();
    }
  }

  private double imageCoverage(List<PlacedImage> images, PDPage page) {
    double pageArea = page.getMediaBox().getWidth() * page.getMediaBox().getHeight();
    if (pageArea <= 0) {
      return 0;
    }
    double covered = 0;
    for (PlacedImage placed : images) {
      covered += Math.abs(placed.matrix.getScalingFactorX() * placed.matrix.getScalingFactorY());
    }
    return Math.min(1.0, covered / pageArea);
  }

  private DocAsset toAsset(PlacedImage placed, PDPage page, List<String> lines, String key) {
    try {
      BufferedImage image = placed.image.getImage();
      if (image == null || image.getWidth() < 20 || image.getHeight() < 20) {
        return null;
      }
      byte[] jpeg = imageNormalizer.toJpeg(image);
      int width = image.getWidth();
      int height = image.getHeight();
      image.flush();
      float pageWidth = page.getMediaBox().getWidth();
      float pageHeight = page.getMediaBox().getHeight();
      double x = placed.matrix.getTranslateX() / pageWidth;
      double y = 1 - (placed.matrix.getTranslateY()
          + Math.abs(placed.matrix.getScalingFactorY())) / pageHeight;
      double w = Math.abs(placed.matrix.getScalingFactorX()) / pageWidth;
      double h = Math.abs(placed.matrix.getScalingFactorY()) / pageHeight;
      String anchor = anchorText(lines, y + h / 2);
      return new DocAsset(key, AssetKind.FIGURE, AssetRole.STEM, jpeg, anchor,
          null, null, null, new double[] {x, y, w, h}, width, height);
    } catch (IOException e) {
      log.warn("PDF 图片转码失败: {}", e.getMessage());
      return null;
    }
  }

  private String anchorText(List<String> lines, double yNormalized) {
    if (lines.isEmpty()) {
      return null;
    }
    int index = (int) Math.round(yNormalized * lines.size());
    index = Math.max(0, Math.min(lines.size() - 1, index));
    return lines.get(index);
  }

  private record PlacedImage(PDImageXObject image, Matrix matrix) {
  }

  private static final class ImageCollector extends PDFStreamEngine {
    private final List<PlacedImage> images = new ArrayList<>();

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
      if ("Do".equals(operator.getName()) && !operands.isEmpty()
          && operands.get(0) instanceof COSName name) {
        PDResources resources = getResources();
        PDXObject xObject = resources == null ? null : resources.getXObject(name);
        if (xObject instanceof PDImageXObject image) {
          images.add(new PlacedImage(image, getGraphicsState().getCurrentTransformationMatrix()));
          return;
        }
        if (xObject instanceof PDFormXObject form) {
          showForm(form);
          return;
        }
      }
      super.processOperator(operator, operands);
    }
  }
}
