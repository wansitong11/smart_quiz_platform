package interview.guide.modules.interview.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 试卷层级树节点。
 * <p>
 * 层级不写死：{@code type} 决定节点语义，{@code depth} 由树深度推导，
 * 因此「卷 → 大题 → 小节 → 材料/小题」与「卷 → 大题 → 小题」两种结构都能表达。
 * QUESTION 节点是唯一叶子题型，携带 {@link InterviewQuestionDTO} 与 questionIndex，
 * questionIndex 是树与 practice_answers 的连接键。
 * </p>
 */
public record PaperNode(
    String id,
    String type,
    int order,
    int depth,
    boolean leaf,
    String title,
    String subtitle,
    String content,
    PaperMeta meta,
    Integer questionIndex,
    InterviewQuestionDTO question,
    List<PaperNode> children
) {

    public static final String TYPE_PAPER = "PAPER";
    public static final String TYPE_SECTION = "SECTION";
    public static final String TYPE_MATERIAL = "MATERIAL";
    public static final String TYPE_QUESTION = "QUESTION";
    public static final String TYPE_INSTRUCTION = "INSTRUCTION";

    /**
     * 节点元信息：分值、题目数、时长，均由子节点汇总（模型给的只是建议）。
     */
    public record PaperMeta(Integer fullScore, Integer questionCount, Integer durationMinutes) {}

    // 注意：这些判断方法必须是包级可见，否则会被 Jackson 当成 isXxx 属性序列化，
    // 与 question 字段冲突（表现为 question 被写成 false）。
    boolean isQuestion() {
        return TYPE_QUESTION.equals(type);
    }

    boolean isSection() {
        return TYPE_SECTION.equals(type);
    }

    boolean isMaterial() {
        return TYPE_MATERIAL.equals(type);
    }

    public List<PaperNode> safeChildren() {
        return children != null ? children : List.of();
    }

    public static PaperNode paper(String id, String title, String subtitle, List<PaperNode> children) {
        return new PaperNode(id, TYPE_PAPER, 0, 0, false, title, subtitle, null,
            new PaperMeta(null, null, null), null, null, children);
    }

    public static PaperNode section(String id, int order, String title, String subtitle,
                                    List<PaperNode> children) {
        return new PaperNode(id, TYPE_SECTION, order, 0, false, title, subtitle, null,
            new PaperMeta(null, null, null), null, null, children);
    }

    public static PaperNode material(String id, int order, String title, String content) {
        return new PaperNode(id, TYPE_MATERIAL, order, 0, true, title, null, content,
            null, null, null, List.of());
    }

    public static PaperNode instruction(String id, int order, String content) {
        return new PaperNode(id, TYPE_INSTRUCTION, order, 0, true, null, null, content,
            null, null, null, List.of());
    }

    public static PaperNode ofQuestion(int order, InterviewQuestionDTO question) {
        return new PaperNode(
            "q" + (question.questionIndex() + 1),
            TYPE_QUESTION,
            order,
            0,
            true,
            null,
            null,
            null,
            new PaperMeta(question.fullScore(), 1, null),
            question.questionIndex(),
            question,
            List.of()
        );
    }

    public PaperNode withChildren(List<PaperNode> newChildren) {
        return new PaperNode(id, type, order, depth, leaf, title, subtitle, content, meta,
            questionIndex, question, newChildren);
    }

    /**
     * 深度优先收集所有 QUESTION 节点（按 questionIndex 升序）。
     */
    public List<PaperNode> collectQuestions() {
        List<PaperNode> collected = new ArrayList<>();
        collectInto(collected);
        return collected;
    }

    private void collectInto(List<PaperNode> collected) {
        if (isQuestion()) {
            collected.add(this);
        }
        for (PaperNode child : safeChildren()) {
            child.collectInto(collected);
        }
    }
}
