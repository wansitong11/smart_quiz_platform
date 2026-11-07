package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KbOcrTextDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 知识库图片识图：只负责一次「图片 -> 文字」的视觉调用。
 */
@Slf4j
@Component
public class KnowledgeBaseImageOcrService {

  private final StructuredOutputInvoker structuredOutputInvoker;
  private final LlmProviderRegistry providerRegistry;
  private final BeanOutputConverter<KbOcrTextDTO> outputConverter =
      new BeanOutputConverter<>(KbOcrTextDTO.class);
  private final String systemPrompt;

  public KnowledgeBaseImageOcrService(
      StructuredOutputInvoker structuredOutputInvoker,
      LlmProviderRegistry providerRegistry,
      ResourceLoader resourceLoader) throws IOException {
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.providerRegistry = providerRegistry;
    this.systemPrompt = resourceLoader
        .getResource("classpath:prompts/knowledgebase-image-ocr-system.st")
        .getContentAsString(StandardCharsets.UTF_8)
        + "\n\n" + outputConverter.getFormat();
  }

  /**
   * PDF / 图片页识图。
   */
  public String ocrPageImage(byte[] jpeg, int pageNo, int pageCount) {
    return ocr(jpeg,
        "这是第 " + pageNo + " 页（共 " + pageCount + " 页）。请转写图片里的全部文字，按阅读顺序输出。",
        "page-" + pageNo);
  }

  /**
   * DOCX 内嵌图识图。
   */
  public String ocrDocxImage(byte[] jpeg, int imageSeq) {
    return ocr(jpeg,
        "这是文档里的第 " + imageSeq + " 张内嵌图片。请转写其中全部文字，按阅读顺序输出。",
        "docx-img-" + imageSeq);
  }

  private String ocr(byte[] jpeg, String userPrompt, String label) {
    if (jpeg == null || jpeg.length == 0) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
          "图片内容为空，无法识别");
    }
    Media media = new Media(MimeTypeUtils.IMAGE_JPEG,
        new ByteArrayResource(jpeg, label + ".jpg"));
    KbOcrTextDTO dto = structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
        providerRegistry.getDefaultVisionChatClient(),
        systemPrompt,
        userPrompt,
        List.of(media),
        outputConverter,
        ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
        "知识库图片识别失败：",
        "知识库识图-" + label,
        log);
    String text = dto == null || dto.text() == null ? "" : dto.text().strip();
    if (text.isBlank()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
          "图片识别没有返回文字，请重试或更换更清晰的图片");
    }
    return text;
  }
}
