package interview.guide.modules.resume.service;

import java.util.List;

/**
 * 结构模型的输出：节点 + 行号范围，文本由后端按行切片。
 */
public record BlockTreeDTO(
    String subjectKey,
    Integer totalScore,
    List<BlockNodeDTO> nodes,
    List<String> warnings
) {

  public record BlockNodeDTO(
      String type,
      int startLine,
      int endLine,
      String title,
      String questionType,
      Integer fullScore,
      List<BlockOptionDTO> options,
      String referenceAnswer,
      List<String> keyPoints,
      String scoringRubric,
      String analysis,
      String category,
      List<String> assetRefs,
      String optionType
  ) {
  }

  public record BlockOptionDTO(String key, String text) {
  }
}
