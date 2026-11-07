package interview.guide.common.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("学生可见解析清洗")
class StudentFacingFeedbackSanitizerTest {

  @Test
  @DisplayName("移除试算、自我纠错和怀疑答案的句子")
  void shouldRemoveDraftReasoningSentences() {
    String source = """
        故选 A。三角形面积S=6，这是确定结论。
        三个扇形面积之和为π/2? 不对，扇形面积之和为π。
        等等，已确认答案是A，这意味着扣除面积是π。
        概率计算公式与选项化简需要按最终条件核对。
        """;

    String result = StudentFacingFeedbackSanitizer.sanitize(source);

    assertThat(result)
        .contains("三角形面积S=6")
        .contains("概率计算公式")
        .doesNotContain("不对")
        .doesNotContain("等等")
        .doesNotContain("已确认答案是A");
  }

  @Test
  @DisplayName("过滤根据答案反推和由答案确定等自洽句")
  void shouldRemoveAnswerDrivenReasoning() {
    String source = """
        三角形总面积为6，扣除三个扇形后可计算概率。
        考点：根据答案反推禁区面积处理逻辑、根据答案确定m的具体数值。
        解析：由已确认答案倒推禁区面积。
        """;

    String result = StudentFacingFeedbackSanitizer.sanitize(source);

    assertThat(result).contains("三角形总面积为6");
    assertThat(result)
        .doesNotContain("根据答案反推")
        .doesNotContain("根据答案确定")
        .doesNotContain("已确认答案倒推");
  }

  @Test
  @DisplayName("过滤句中出现的自我纠错和重新审视片段")
  void shouldRemoveSelfCorrectionInsideSentence() {
    String source = "先判断三角函数值，例如1+sin(2x)不对，可能是求sin(2x)+...。"
        + "重新审视题目条件后，可确定最终表达式。";

    String result = StudentFacingFeedbackSanitizer.sanitize(source);

    assertThat(result)
        .doesNotContain("不对")
        .doesNotContain("可能是")
        .doesNotContain("重新审视");
  }
}
