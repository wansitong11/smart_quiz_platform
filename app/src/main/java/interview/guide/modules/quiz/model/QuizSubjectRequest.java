package interview.guide.modules.quiz.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建/编辑自定义科目的入参。
 */
public record QuizSubjectRequest(
    @NotBlank(message = "科目名称不能为空")
    @Size(max = 64, message = "科目名称最多 64 字")
    String name,

    @Size(max = 200, message = "科目说明最多 200 字")
    String description,

    @NotBlank(message = "所属考试不能为空")
    String examCode,

    @Valid
    @NotEmpty(message = "至少添加一个考点")
    List<KnowledgePointInput> knowledgePoints,

    @Valid
    @NotEmpty(message = "至少选择一种题型")
    List<QuestionTypeInput> questionTypes,

    Integer suggestedMinutes,

    @Size(max = 8000, message = "大纲文本最多 8000 字")
    String outlineText
) {

  public record KnowledgePointInput(
      @NotBlank(message = "考点名称不能为空")
      @Size(max = 64, message = "考点名称最多 64 字")
      String label
  ) {
  }

  public record QuestionTypeInput(
      @NotBlank(message = "题型不能为空")
      String type,

      @NotNull(message = "题型默认分不能为空")
      Integer defaultScore,

      @NotNull(message = "题型权重不能为空")
      Integer weight
  ) {
  }
}
