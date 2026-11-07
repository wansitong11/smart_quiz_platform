package interview.guide.modules.interview.model;

import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;

import java.util.List;

/**
 * 异步出题请求快照：壳会话落库时保存，worker 在没有请求上下文时据此恢复出题入参。
 */
public record QuestionGenRequestSnapshot(
    String resumeText,
    Long resumeId,
    String skillId,
    String difficulty,
    int questionCount,
    List<CategoryDTO> customCategories,
    String jdText,
    String llmProvider
) {}
