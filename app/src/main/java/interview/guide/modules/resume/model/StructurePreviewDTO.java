package interview.guide.modules.resume.model;

import java.util.List;

/**
 * 结构预览：确认切题前展示"部分 → 大题 → 材料 → 题目"。
 */
public record StructurePreviewDTO(
    Long paperId,
    String subjectId,
    int totalScore,
    int questionCount,
    List<String> warnings,
    List<NodeDTO> nodes
) {

  public record NodeDTO(
      String type,
      String title,
      String text,
      String questionType,
      Integer fullScore,
      Integer pageFrom,
      Integer pageTo,
      List<String> assetIds,
      List<NodeDTO> children
  ) {
  }
}
