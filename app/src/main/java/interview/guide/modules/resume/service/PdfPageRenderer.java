package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * PDF 逐页处理：文字层抽取 + 单页渲染。
 * <p>
 * 服务器内存有限，必须"一次一页"：禁止把 10 页全部渲染进内存。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfPageRenderer {

  /** 识别用渲染分辨率：180 DPI 能看清小学试卷的小字，缩放后再压到 2000px。 */
  private static final float RENDER_DPI = 180f;

  private final PageImageNormalizer imageNormalizer;

  public int pageCount(byte[] pdfBytes) {
    try (PDDocument document = PDDocument.load(pdfBytes)) {
      return document.getNumberOfPages();
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "无法打开 PDF，请确认文件未损坏");
    }
  }

  public String extractPageText(byte[] pdfBytes, int pageNo) {
    try (PDDocument document = PDDocument.load(pdfBytes)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      stripper.setStartPage(pageNo);
      stripper.setEndPage(pageNo);
      return stripper.getText(document);
    } catch (IOException e) {
      log.warn("抽取 PDF 第 {} 页文字层失败，将按图片页处理", pageNo, e);
      return "";
    }
  }

  /**
   * 逐页回调：渲染成 JPEG 交给调用方，回调返回后即可释放该页图像。
   */
  public int forEachPage(byte[] pdfBytes, PageConsumer consumer) {
    try (PDDocument document = PDDocument.load(pdfBytes)) {
      PDFRenderer renderer = new PDFRenderer(document);
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      int pageCount = document.getNumberOfPages();
      for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
        String text;
        try {
          stripper.setStartPage(pageNo);
          stripper.setEndPage(pageNo);
          text = stripper.getText(document);
        } catch (IOException textError) {
          log.warn("抽取 PDF 第 {} 页文字层失败，将按图片页处理", pageNo, textError);
          text = "";
        }
        BufferedImage image = renderer.renderImageWithDPI(pageNo - 1, RENDER_DPI);
        byte[] jpeg;
        try {
          jpeg = imageNormalizer.toJpeg(image);
        } finally {
          image.flush();
        }
        consumer.accept(pageNo, text, jpeg);
      }
      return pageCount;
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "PDF 逐页渲染失败，请确认文件未损坏");
    }
  }

  @FunctionalInterface
  public interface PageConsumer {
    void accept(int pageNo, String text, byte[] jpegBytes);
  }
}
