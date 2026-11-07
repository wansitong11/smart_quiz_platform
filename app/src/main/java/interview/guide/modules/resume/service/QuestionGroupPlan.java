package interview.guide.modules.resume.service;

import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;

import java.util.List;

/**
 * 题组计划：连续 MATERIAL 后接连续 QUESTION 的确定性分组。
 */
public record QuestionGroupPlan(
    String groupId,
    int startLine,
    int endLine,
    List<BlockNodeDTO> materials,
    List<BlockNodeDTO> questions
) {

  public QuestionGroupPlan {
    materials = materials == null ? List.of() : List.copyOf(materials);
    questions = questions == null ? List.of() : List.copyOf(questions);
  }
}
