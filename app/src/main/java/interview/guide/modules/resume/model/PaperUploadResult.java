package interview.guide.modules.resume.model;

/**
 * 扫描件 / 多页图片上传结果。
 */
public record PaperUploadResult(
    Long paperId,
    int pageCount,
    RecognitionStatus recognitionStatus,
    boolean duplicate,
    String message
) {
}
