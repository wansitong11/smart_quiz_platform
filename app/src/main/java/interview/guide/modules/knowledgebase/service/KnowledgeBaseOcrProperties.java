package interview.guide.modules.knowledgebase.service;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 知识库识图配置。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.rag.ocr")
public class KnowledgeBaseOcrProperties {

  /**
   * 识图总开关；关闭后图片上传被拒绝，PDF/DOCX 退回 Tika 纯文字。
   */
  private boolean enabled = true;

  /**
   * 单份知识库允许识图的页数上限（图片=1 页；PDF 只累计 IMAGE 页）。
   */
  @Min(1)
  private int maxPages = 10;

  /**
   * 单份 DOCX 允许识图的内嵌图片数上限。
   */
  @Min(1)
  private int maxDocxImages = 40;
}
