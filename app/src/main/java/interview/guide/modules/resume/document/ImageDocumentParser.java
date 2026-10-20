package interview.guide.modules.resume.document;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.service.PageImageNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 纯图片：一张图 = 一页，文本与资源交给视觉模型。
 */
@Component
@RequiredArgsConstructor
public class ImageDocumentParser implements DocumentParser {

  private final PageImageNormalizer imageNormalizer;

  @Override
  public boolean supports(String contentType, String filename) {
    if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
      return true;
    }
    if (filename == null) {
      return false;
    }
    String lower = filename.toLowerCase();
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
  }

  @Override
  public ParsedDocument parse(byte[] bytes, String filename, String contentType) {
    try {
      byte[] jpeg = imageNormalizer.normalizeUploadedImage(bytes);
      ParsedPage page = new ParsedPage(1, PageSource.IMAGE, List.of(), List.of(), jpeg, false, null);
      return new ParsedDocument("IMAGE", List.of(page), List.of());
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "读取图片失败，请确认文件未损坏");
    }
  }
}
