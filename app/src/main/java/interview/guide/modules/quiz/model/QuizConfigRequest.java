package interview.guide.modules.quiz.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建模拟测验请求（组卷参数）。
 */
public record QuizConfigRequest(
    @NotBlank(message = "学科不能为空")
    String skillId,

    /** 难度：junior / mid / senior（基础 / 中等 / 综合） */
    String difficulty,

    @Min(value = 1, message = "题量至少 1 题")
    @Max(value = 30, message = "题量最多 30 题")
    Integer questionCount,

    /** 知识板块 key 列表（对应 skill.meta.yml 的 categories），空表示全科随机 */
    List<String> categories,

    /** 期望题型（SINGLE_CHOICE / SHORT_ANSWER ...），空表示按学科自动配比 */
    List<String> questionTypes,

    /** 出题使用的 Provider */
    String llmProvider
) {
}
