package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.InterviewEvaluationProperties;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 客观题确定性判分器。
 * <p>
 * 选择题（单选 / 多选）与可规范比对的填空题不需要调用大模型：
 * 与参考答案直接比对即可得出得分率与评语，结果稳定、可复现、零 token 成本。
 * 无法确定性判定的题目（主观题、需要等价性判断的填空）返回空，交由模型批改。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObjectiveAnswerGrader {

    /** 「答案/故选/选择 + 选项字母」形态 */
    private static final Pattern CHOICE_ANSWER_PATTERN =
        Pattern.compile("(?:答案|故选|选择|选)\\s*[:：是为]?\\s*[（(]?([A-H]{1,8})[）)]?");

    private final InterviewEvaluationProperties properties;

    /**
     * 判分结果。
     *
     * @param scoreRate 得分率（0-100）
     * @param feedback  评语
     */
    public record Grade(int scoreRate, String feedback) {}

    /**
     * 尝试确定性判分；返回空表示该题需要模型批改。
     */
    public Optional<Grade> grade(InterviewQuestionDTO question) {
        if (question == null) {
            return Optional.empty();
        }
        String answer = question.userAnswer();
        boolean blank = answer == null || answer.isBlank();
        String questionType = question.questionType();

        if (question.isChoiceQuestion()) {
            if (blank) {
                return Optional.of(new Grade(0,
                    withAnalysis(question, "未作答。正确答案：" + question.referenceAnswer())));
            }
            return gradeChoice(question);
        }
        if (InterviewQuestionDTO.TYPE_FILL_BLANK.equals(questionType)) {
            return gradeFillBlank(question, blank);
        }
        return Optional.empty();
    }

    private Optional<Grade> gradeChoice(InterviewQuestionDTO question) {
        Set<String> student = extractChoiceKeys(question.userAnswer(), question.options());
        Set<String> expected = extractChoiceKeys(question.referenceAnswer(), question.options());
        if (expected.isEmpty()) {
            // 参考答案不是选项字母，交回模型判断
            return Optional.empty();
        }
        if (student.isEmpty()) {
            return Optional.of(new Grade(0,
                withAnalysis(question, "未作答。正确答案：" + String.join("", expected))));
        }
        if (student.equals(expected)) {
            return Optional.of(new Grade(100,
                withAnalysis(question, "选择正确。正确答案：" + String.join("", expected))));
        }
        boolean isMulti = InterviewQuestionDTO.TYPE_MULTI_CHOICE.equals(question.questionType())
            || expected.size() > 1;
        if (isMulti) {
            boolean subset = expected.containsAll(student);
            if (subset && properties.isMultiChoicePartialCredit()) {
                int rate = Math.max(0, Math.min(100, properties.getMultiChoicePartialCreditRate()));
                return Optional.of(new Grade(rate, withAnalysis(question,
                    "漏选：" + String.join("", student) + " 未选全，正确答案 "
                        + String.join("", expected) + "，按漏选给 " + rate + "% 分。")));
            }
            return Optional.of(new Grade(0, withAnalysis(question,
                "选择错误：你的选择了 " + String.join("", student)
                    + "，正确答案 " + String.join("", expected) + "。多选题错选不得分。")));
        }
        return Optional.of(new Grade(0, withAnalysis(question,
            "选择错误：你选了 " + String.join("", student)
                + "，正确答案 " + String.join("", expected) + "。")));
    }

    private Optional<Grade> gradeFillBlank(InterviewQuestionDTO question, boolean blank) {
        String expected = question.referenceAnswer();
        if (expected == null || expected.isBlank()) {
            return Optional.empty();
        }
        if (blank) {
            return Optional.of(new Grade(0, withAnalysis(question, "未作答。正确答案：" + expected)));
        }
        if (normalizeBlank(question.userAnswer()).equals(normalizeBlank(expected))) {
            return Optional.of(new Grade(100, withAnalysis(question, "填写正确。")));
        }
        // 纯数字/字母答案（如 1/2、0.5、AB）没有等价改写空间，直接判错；其余交模型判断等价性
        if (isPlainAnswer(expected)) {
            return Optional.of(new Grade(0, withAnalysis(question,
                "填写错误：你的答案「" + question.userAnswer().trim()
                    + "」，正确答案「" + expected.trim() + "」。")));
        }
        return Optional.empty();
    }

    /**
     * 在判分结论后补上题目解析与考点，让客观题评语也能讲清「为什么」。
     * <p>
     * 解析来自切题阶段抽取的 analysis；没有解析时退回考点与评分标准，
     * 三者都没有则只保留判分结论。
     * </p>
     */
    private String withAnalysis(InterviewQuestionDTO question, String conclusion) {
        StringBuilder sb = new StringBuilder(conclusion);
        String analysis = question.analysis();
        boolean hasAnalysis = analysis != null && !analysis.isBlank();
        if (hasAnalysis) {
            sb.append("\n解析：").append(analysis.trim());
        }
        List<String> keyPoints = question.keyPoints();
        if (keyPoints != null && !keyPoints.isEmpty()) {
            String points = String.join("、", keyPoints.stream()
                .filter(point -> point != null && !point.isBlank())
                .toList());
            if (!points.isBlank()) {
                sb.append("\n考点：").append(points);
            }
        }
        String rubric = question.scoringRubric();
        if (!hasAnalysis && (keyPoints == null || keyPoints.isEmpty())
            && rubric != null && !rubric.isBlank()) {
            sb.append("\n评分标准：").append(rubric.trim());
        }
        return sb.toString();
    }

    /**
     * 从答案文本中抽取选项字母（如 "BD"、"B、D"、"答案是 B"）。
     * 只认「纯选项字母」或「答案/故选 + 选项字母」两种形态，避免把主观作答里的字母误判成选项。
     */
    Set<String> extractChoiceKeys(String text, List<QuestionOption> options) {
        Set<String> keys = new TreeSet<>();
        if (text == null || text.isBlank() || options == null || options.isEmpty()) {
            return keys;
        }
        Set<String> valid = new LinkedHashSet<>();
        for (QuestionOption option : options) {
            valid.add(option.key().toUpperCase());
        }
        String upper = text.toUpperCase().trim();

        // 形态一：只有选项字母与分隔符，如 "B"、"BD"、"B、D"、（A）
        String stripped = upper.replaceAll("[\\s,，、;；/和与及.。()（）]", "");
        if (!stripped.isEmpty() && isAllKeys(stripped, valid)) {
            for (char c : stripped.toCharArray()) {
                keys.add(String.valueOf(c));
            }
            return keys;
        }

        // 形态二：「答案/故选/选择 + 选项字母」，如 "答案是 B"、"故选 BD"
        java.util.regex.Matcher matcher = CHOICE_ANSWER_PATTERN.matcher(upper);
        if (matcher.find()) {
            for (char c : matcher.group(1).toCharArray()) {
                String key = String.valueOf(c);
                if (valid.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    private boolean isAllKeys(String text, Set<String> valid) {
        for (char c : text.toCharArray()) {
            if (!valid.contains(String.valueOf(c))) {
                return false;
            }
        }
        return true;
    }

    private String normalizeBlank(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace('\u3000', ' ')
            .replaceAll("\\s+", "")
            .replaceAll("[。．.,，；;：:！!？?、\"'“”‘’（）()《》<>]", "")
            .toLowerCase();
    }

    private boolean isPlainAnswer(String text) {
        String normalized = normalizeBlank(text);
        return !normalized.isBlank() && normalized.matches("[0-9a-z/\\-+.=]+");
    }
}
