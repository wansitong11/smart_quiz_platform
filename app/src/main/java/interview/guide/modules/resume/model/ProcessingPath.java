package interview.guide.modules.resume.model;

/**
 * 试卷上传后的处理路径：纯文字直接切题，或走逐页识图与预览校对。
 */
public enum ProcessingPath {
  /** 原始文件已接收，等待异步解析 */
  INPUT_PENDING,
  DIRECT_EXTRACTION,
  VISION_RECOGNITION
}
