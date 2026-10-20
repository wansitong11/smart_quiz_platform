package interview.guide.modules.resume.model;

/**
 * 图片 / 扫描件识别状态（与切题的 analysis_status 分开存）。
 */
public enum RecognitionStatus {
  /** 文字类材料，未走页级识别 */
  NONE,
  /** 已入队，等待识别 */
  PENDING,
  /** 逐页识别中 */
  PROCESSING,
  /** 页级识别结束，等待用户预览校对 */
  NEEDS_REVIEW,
  /** 用户已确认，切题任务进行中 */
  EXTRACTING,
  /** 切题完成，可开始作答 */
  READY,
  /** 识别失败（全部页失败 / 文件损坏） */
  FAILED
}
