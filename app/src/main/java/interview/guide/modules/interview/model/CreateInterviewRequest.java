package interview.guide.modules.interview.model;

import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建面试会话请求
 */
public record CreateInterviewRequest(
    String resumeText,      // 简历文本内容（可选，无简历时为通用面试）

    @Min(value = 3, message = "题目数量最少3题")
    @Max(value = 20, message = "题目数量最多20题")
    int questionCount,      // 面试题目数量 (3-20)

    Long resumeId,          // 简历ID（可选，无简历时不传）

    Boolean forceCreate,    // 是否强制创建新会话（忽略未完成的会话），默认为 false

    String llmProvider,     // LLM提供商

    @NotBlank(message = "练习学科不能为空")
    String skillId,         // 学科 / 自定义练习 ID（如 chinese, math, english, custom 等）

    String difficulty,      // 难度级别: junior / mid / senior

    List<CategoryDTO> customCategories,   // 自定义练习的分类（考纲 / 资料解析结果）

    String jdText,                         // 考纲 / 资料原文（自定义练习时作为出题依据）

    String requestId                       // 创建请求幂等键，刷新/重试时复用同一会话
) {}
