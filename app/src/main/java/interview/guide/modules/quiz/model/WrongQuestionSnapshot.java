package interview.guide.modules.quiz.model;

import interview.guide.modules.interview.model.InterviewQuestionDTO;

import java.util.List;

/**
 * 错题原题快照。内部答案键保存在 question 中，只在后端判分/重练时读取。
 */
public record WrongQuestionSnapshot(
    InterviewQuestionDTO question,
    String sectionTitle,
    String sectionSubtitle,
    List<MaterialBlock> materials,
    Long paperId,
    List<String> assetIds
) {

  public WrongQuestionSnapshot {
    materials = materials == null ? List.of() : List.copyOf(materials);
    assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
  }

  public WrongQuestionSnapshot withPaperId(Long fallbackPaperId) {
    return paperId == null && fallbackPaperId != null
        ? new WrongQuestionSnapshot(question, sectionTitle, sectionSubtitle, materials,
            fallbackPaperId, assetIds)
        : this;
  }

  /**
   * 材料块同时保存展示文本与可能存在的图片资源，源试卷仍在时可一并回顾。
   */
  public record MaterialBlock(String title, String content, List<String> assetIds) {

    public MaterialBlock {
      assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
    }

    public MaterialBlock(String title, String content) {
      this(title, content, List.of());
    }
  }
}
