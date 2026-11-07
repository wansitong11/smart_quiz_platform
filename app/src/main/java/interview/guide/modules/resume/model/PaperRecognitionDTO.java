package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;

import java.util.List;

/**
 * 试卷识别状态与页列表（前端轮询 + 预览校对）。
 */
public record PaperRecognitionDTO(
    Long paperId,
    RecognitionStatus recognitionStatus,
    String recognitionError,
    Integer pageCount,
    List<PaperPageDTO> pages,
    List<Integer> questionNos,
    List<String> warnings,
    List<PaperAssetDTO> assets,
    List<Integer> geometryMissingPages,
    AsyncTaskStatus inputStatus,
    String inputError,
    boolean hasActiveAttempt
) {

  public record PaperPageDTO(
      int pageNo,
      PageSource source,
      PageStatus status,
      String text,
      boolean hasFigure,
      String notes,
      String error,
      String imageUrl,
      String geometryStatus,
      int assetCount
  ) {
  }

  public record PaperAssetDTO(
      String key,
      Integer pageNo,
      String kind,
      String role,
      String anchorText,
      Integer optionCount,
      String layout,
      Integer width,
      Integer height
  ) {
  }
}
