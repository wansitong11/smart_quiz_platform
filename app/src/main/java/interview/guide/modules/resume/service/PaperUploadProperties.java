package interview.guide.modules.resume.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 试卷上传约束配置。视觉物理页与纯文字逻辑页分开限额。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.paper.upload")
public class PaperUploadProperties {

  /** 单份试卷允许的视觉识别物理页上限（图片 / 扫描页）。 */
  private int maxVisionPages = 10;

  /** 纯文字材料合并后的字符上限，超出拒绝，防止极端输入打爆切题上下文。 */
  private int maxTextChars = 30000;
}
