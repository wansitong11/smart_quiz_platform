package interview.guide.modules.interview.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 整卷一次交卷请求。
 * <p>
 * 用于试卷 / 练习材料场景：学生在一页里答完整卷（可跳题、可回改），
 * 交卷时一次性提交全部答案，后端批量落库并触发批改。
 * </p>
 */
public record BatchSubmitAnswerRequest(
    @NotEmpty(message = "答案列表不能为空")
    @Size(max = 200, message = "单次交卷题目数过多")
    @Valid
    List<AnswerItem> answers
) {

    /**
     * 单题作答。
     * <p>
     * - 选择题：传 selectedKeys（如 ["B"]、["A","C"]），answer 可由后端按 key 拼接；<br>
     * - 填空 / 主观题：传 answer 文本；<br>
     * - answer 允许为空字符串（表示该题未作答，按 0 分处理）。
     * </p>
     */
    public record AnswerItem(
        @NotNull(message = "问题索引不能为空")
        @Min(value = 0, message = "问题索引无效")
        @Max(value = 199, message = "问题索引无效")
        Integer questionIndex,

        /** 题型（前端回传，用于选择题规范化与确定性判分；可为空） */
        String questionType,

        /** 选择题选中的选项 key，如 ["B"]、["A","C"] */
        List<String> selectedKeys,

        String answer
    ) {}
}
