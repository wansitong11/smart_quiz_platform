package interview.guide.common.evaluation;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 移除会直接展示给学生的模型草稿式推理。
 *
 * <p>结构化模型偶尔会在最终字段里留下“等等”“不对”“让我重新算”等
 * 自我纠错过程。这里按完整句子过滤，避免把内部试算暴露给学生。</p>
 */
public final class StudentFacingFeedbackSanitizer {

  private static final Pattern DRAFT_SENTENCE = Pattern.compile(
      "^(?:等等|不对(?:[，,。！？?!]|$)|让我|我们重新|重新算|逆向|题目本身"
          + "|按给定答案|按已确认答案|给定答案|似乎|可能是|先看一下|再检查|验算后)");
  private static final Pattern DRAFT_FRAGMENT = Pattern.compile(
      "题目本身(?:有误|有问题)|按给定答案|逆向(?:推演|解释)|已确认答案是"
          + "|(?:根据|按照|按|由|为了).{0,4}(?:给定|已确认|参考)?答案"
          + ".{0,6}(?:反推|倒推|逆向|确定|匹配|处理)"
          + "|不对[，,]|等等[，,]|可能是|重新审视|重新计算|反向推导");
  private static final String HIDDEN_MESSAGE =
      "解析中包含模型内部推理，已隐藏。请结合题目、参考答案与考点核对本题。";

  private StudentFacingFeedbackSanitizer() {
  }

  public static String sanitize(String feedback) {
    if (feedback == null || feedback.isBlank()) {
      return feedback;
    }
    String normalized = feedback.replace("\r\n", "\n").replace('\r', '\n').trim();
    String kept = Arrays.stream(normalized.split("(?<=[。！？?!；;])|\\n+"))
        .map(String::trim)
        .filter(sentence -> !sentence.isBlank())
        .filter(sentence -> !DRAFT_SENTENCE.matcher(sentence).find())
        .map(StudentFacingFeedbackSanitizer::removeDraftFragments)
        .filter(sentence -> !sentence.isBlank())
        .collect(Collectors.joining());
    return kept.isBlank() ? HIDDEN_MESSAGE : kept;
  }

  private static String removeDraftFragments(String sentence) {
    if (!DRAFT_FRAGMENT.matcher(sentence).find()) {
      return sentence;
    }
    String cleaned = Arrays.stream(sentence.split("(?<=[，,、；;])"))
        .map(String::trim)
        .filter(clause -> !DRAFT_FRAGMENT.matcher(clause).find())
        .collect(Collectors.joining());
    return cleaned.matches("(?:考点|解析)[：:]?") ? "" : cleaned;
  }
}
