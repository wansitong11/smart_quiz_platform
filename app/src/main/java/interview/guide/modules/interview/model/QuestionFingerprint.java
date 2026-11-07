package interview.guide.modules.interview.model;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 题目稳定指纹：题型 + 规范化题干 + 选项 + 来源页。
 *
 * <p>只由题目本身决定，不包含答案和解析；同一道题在多次切题/重新分析之间
 * 只要题干不变，指纹就保持不变，可用于逐题复用已有答案键。</p>
 */
public final class QuestionFingerprint {

  private QuestionFingerprint() {
  }

  public static String of(InterviewQuestionDTO question) {
    if (question == null) {
      return "";
    }
    String options = question.options() == null ? "" : question.options().stream()
        .filter(option -> option != null)
        .map(option -> normalize(option.key()) + ":" + normalize(option.text()))
        .sorted()
        .collect(Collectors.joining(","));
    String raw = normalize(question.questionType())
        + "|" + normalize(question.question())
        + "|" + options
        + "|" + question.sourcePageFrom() + "-" + question.sourcePageTo();
    return Integer.toHexString(raw.hashCode());
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.replaceAll("\\s+", "")
        .replace('（', '(').replace('）', ')')
        .replace('，', ',').replace('。', '.')
        .toLowerCase(Locale.ROOT)
        .trim();
  }
}
