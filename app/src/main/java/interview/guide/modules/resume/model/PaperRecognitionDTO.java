package interview.guide.modules.resume.model;

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
    List<PaperAssetDTO> assets
) {

  public record PaperPageDTO(
      int pageNo,
      PageSource source,
      PageStatus status,
      String text,
      boolean hasFigure,
      String notes,
      String error,
      String imageUrl
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
