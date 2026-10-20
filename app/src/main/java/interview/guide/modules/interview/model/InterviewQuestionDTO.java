package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试问题DTO
 * type 由 Skill category key 驱动（如 MYSQL、CSS、DYNAMIC_PROGRAMMING 等），不再使用枚举
 */
public record InterviewQuestionDTO(
    int questionIndex,
    String question,
    String type,           // Skill category key，如 "MYSQL"、"CSS"、"DP"
    String category,       // 展示用标签，如 "MySQL"、"CSS"、"动态规划"
    String topicSummary,   // 知识点摘要，如 "Redis RDB/AOF 持久化对比"，用于历史去重压缩
    String userAnswer,
    Integer score,
    String feedback,
    boolean isFollowUp,
    Integer parentQuestionIndex,
    String referenceAnswer,
    List<String> keyPoints,
    String scoringRubric,
    String sourceContext,
    Integer fullScore,     // 该题分值（试卷 / 练习材料切题场景使用；自动出题场景为 null）
    String questionType,   // 题型：SINGLE_CHOICE / MULTI_CHOICE / FILL_BLANK / SHORT_ANSWER / READING / WRITING / OTHER
    List<QuestionOption> options, // 选择题选项（无选项时为 null）
    String analysis,       // 题目解析（切题阶段抽取，用于批改评语；没有时为 null）
    Integer sourcePageFrom, // 题目起始页（扫描件识别时写入，用于"查看原页"）
    Integer sourcePageTo    // 题目结束页（跨页题 from < to）
) {

    /**
     * 选择题选项：key 为 A/B/C/D，text 为选项正文（不含标签）。
     */
    public record QuestionOption(String key, String text, String assetId) {
      public QuestionOption(String key, String text) {
        this(key, text, null);
      }
    }

    /** 单选题 */
    public static final String TYPE_SINGLE_CHOICE = "SINGLE_CHOICE";
    /** 多选题 */
    public static final String TYPE_MULTI_CHOICE = "MULTI_CHOICE";
    /** 填空题 */
    public static final String TYPE_FILL_BLANK = "FILL_BLANK";
    /** 简答题 */
    public static final String TYPE_SHORT_ANSWER = "SHORT_ANSWER";
    /** 阅读题 */
    public static final String TYPE_READING = "READING";
    /** 写作题 */
    public static final String TYPE_WRITING = "WRITING";
    /** 其他题型 */
    public static final String TYPE_OTHER = "OTHER";

    /**
     * 是否为选择题（单/多选）。选项齐全才按选择题渲染与判分。
     */
    public boolean isChoiceQuestion() {
        return options != null && options.size() >= 2
            && (TYPE_SINGLE_CHOICE.equals(questionType) || TYPE_MULTI_CHOICE.equals(questionType));
    }

    public static InterviewQuestionDTO create(int index, String question, String type, String category) {
        return new InterviewQuestionDTO(
            index, question, type, category, null, null, null, null, false, null, null, null, null, null, null,
            null, null, null, null, null);
    }

    public static InterviewQuestionDTO create(int index, String question, String type, String category,
                                               String topicSummary, boolean isFollowUp, Integer parentQuestionIndex) {
        return new InterviewQuestionDTO(
            index, question, type, category, topicSummary, null, null, null, isFollowUp, parentQuestionIndex,
            null, null, null, null, null, null, null, null, null, null);
    }

    public static InterviewQuestionDTO fromQuestionBank(int index, String question, String type,
                                                        String category, String topicSummary,
                                                        String referenceAnswer, List<String> keyPoints,
                                                        String scoringRubric, String sourceContext) {
        return new InterviewQuestionDTO(
            index, question, type, category, topicSummary, null, null, null, false, null,
            referenceAnswer, keyPoints, scoringRubric, sourceContext, null, null, null, null, null, null);
    }

    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, answer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext, fullScore,
            questionType, options, analysis, sourcePageFrom, sourcePageTo);
    }

    public InterviewQuestionDTO withEvaluation(int score, String feedback) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext, fullScore,
            questionType, options, analysis, sourcePageFrom, sourcePageTo);
    }

    /**
     * 复制为题目的初始状态：清空作答、得分与评语。
     * 同一份试卷再次作答时用，题干、选项、参考答案与评分标准原样保留。
     */
    public InterviewQuestionDTO withoutAnswer() {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, null, null, null,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext, fullScore,
            questionType, options, analysis, sourcePageFrom, sourcePageTo);
    }

    /** 写入题目来源页；没有页码信息时原样返回。 */
    public InterviewQuestionDTO withSourcePages(Integer pageFrom, Integer pageTo) {
        if (pageFrom == null) {
            return this;
        }
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext, fullScore,
            questionType, options, analysis, pageFrom, pageTo);
    }

    /** 替换选项（图形选项在切题后回填 assetId）。 */
    public InterviewQuestionDTO withOptions(List<QuestionOption> newOptions) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext,
            fullScore, questionType, newOptions, analysis, sourcePageFrom, sourcePageTo);
    }
}
