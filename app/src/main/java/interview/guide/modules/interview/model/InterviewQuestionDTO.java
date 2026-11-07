package interview.guide.modules.interview.model;

import interview.guide.common.ai.AnswerKeySupport;
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
    String analysis,       // 题目解析（切题阶段抽取，报告详情单独展示；没有时为 null）
    Integer sourcePageFrom, // 题目起始页（扫描件识别时写入，用于"查看原页"）
    Integer sourcePageTo,   // 题目结束页（跨页题 from < to）
    String visualContext,   // 图形、材料或选项摘要（内部答案键字段）
    String answerKeySource, // TEXT / VISION / MANUAL
    Integer answerKeyConfidence, // 0-100
    boolean requiresVisualGrading // 后端批改时必须结合原图
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
    /** 判断题（固定 正确/错误 选项，本地判分） */
    public static final String TYPE_TRUE_FALSE = "TRUE_FALSE";
    /** 填空题 */
    public static final String TYPE_FILL_BLANK = "FILL_BLANK";
    /** 简答题 */
    public static final String TYPE_SHORT_ANSWER = "SHORT_ANSWER";
    /** 阅读题 */
    public static final String TYPE_READING = "READING";
    /** 写作题 */
    public static final String TYPE_WRITING = "WRITING";
    /** 案例分析题（长文本 + 评分量规，主观批改） */
    public static final String TYPE_CASE_ANALYSIS = "CASE_ANALYSIS";
    /** 其他题型 */
    public static final String TYPE_OTHER = "OTHER";

    /**
     * 是否为选择题（单/多/判断）。选项齐全才按选择题渲染与判分。
     */
    public boolean isChoiceQuestion() {
        return options != null && options.size() >= 2
            && (TYPE_SINGLE_CHOICE.equals(questionType) || TYPE_MULTI_CHOICE.equals(questionType)
                || TYPE_TRUE_FALSE.equals(questionType));
    }

    /**
     * 是否走确定性判分（不调用模型）：单选 / 多选 / 判断 / 填空。
     * 判断由 isChoiceQuestion 覆盖，因此这里不再显式判断 TYPE_TRUE_FALSE。
     */
    public boolean isDeterministicGrading() {
        return isChoiceQuestion()
            || TYPE_SINGLE_CHOICE.equals(questionType)
            || TYPE_MULTI_CHOICE.equals(questionType)
            || TYPE_FILL_BLANK.equals(questionType);
    }

    /**
     * 答案键是否可用：有候选答案且不是「暂缺」占位符。
     */
    public boolean hasUsableAnswerKey() {
        return !AnswerKeySupport.isMissing(referenceAnswer);
    }

    /**
     * 是否携带评分依据（参考答案 / 评分规则 / 评分要点）。
     * 批改上下文拼装与参考答案回填都以此判断，避免空参考信息进入 Prompt 或报告。
     */
    public boolean hasReferenceMaterial() {
        return !AnswerKeySupport.isMissing(referenceAnswer)
            || (scoringRubric != null && !scoringRubric.isBlank())
            || (keyPoints != null && !keyPoints.isEmpty());
    }

    public static InterviewQuestionDTO create(int index, String question, String type, String category) {
        return new InterviewQuestionDTO(
            index, question, type, category, null, null, null, null, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, false);
    }

    public static InterviewQuestionDTO create(int index, String question, String type, String category,
                                               String topicSummary, boolean isFollowUp, Integer parentQuestionIndex) {
        return new InterviewQuestionDTO(
            index, question, type, category, topicSummary, null, null, null, isFollowUp, parentQuestionIndex,
            null, null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    public static InterviewQuestionDTO fromQuestionBank(int index, String question, String type,
                                                        String category, String topicSummary,
                                                        String referenceAnswer, List<String> keyPoints,
                                                        String scoringRubric, String sourceContext) {
        return new InterviewQuestionDTO(
            index, question, type, category, topicSummary, null, null, null, false, null,
            referenceAnswer, keyPoints, scoringRubric, sourceContext, null, null, null, null, null, null,
            null, null, null, false);
    }

    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, answer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext, fullScore,
            questionType, options, analysis, sourcePageFrom, sourcePageTo,
            visualContext, answerKeySource, answerKeyConfidence, requiresVisualGrading);
    }

    public InterviewQuestionDTO withQuestionIndex(int index) {
        return new InterviewQuestionDTO(
            index, question, type, category, topicSummary, userAnswer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext, fullScore,
            questionType, options, analysis, sourcePageFrom, sourcePageTo,
            visualContext, answerKeySource, answerKeyConfidence, requiresVisualGrading);
    }

    public InterviewQuestionDTO withEvaluation(int score, String feedback) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext, fullScore,
            questionType, options, analysis, sourcePageFrom, sourcePageTo,
            visualContext, answerKeySource, answerKeyConfidence, requiresVisualGrading);
    }

    /**
     * 复制为题目的初始状态：清空作答、得分与评语。
     * 同一份试卷再次作答时用，题干、选项、参考答案与评分标准原样保留。
     */
    public InterviewQuestionDTO withoutAnswer() {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, null, null, null,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext, fullScore,
            questionType, options, analysis, sourcePageFrom, sourcePageTo,
            visualContext, answerKeySource, answerKeyConfidence, requiresVisualGrading);
    }

    /** 写入题目来源页；没有页码信息时原样返回。 */
    public InterviewQuestionDTO withSourcePages(Integer pageFrom, Integer pageTo) {
        if (pageFrom == null) {
            return this;
        }
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext, fullScore,
            questionType, options, analysis, pageFrom, pageTo,
            visualContext, answerKeySource, answerKeyConfidence, requiresVisualGrading);
    }

    /** 替换选项（图形选项在切题后回填 assetId）。 */
    public InterviewQuestionDTO withOptions(List<QuestionOption> newOptions) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext,
            fullScore, questionType, newOptions, analysis, sourcePageFrom, sourcePageTo,
            visualContext, answerKeySource, answerKeyConfidence, requiresVisualGrading);
    }

    /** 写入内部答案键；答案键不会返回普通作答接口。 */
    public InterviewQuestionDTO withAnswerKey(AnswerKeyDTO key) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
            isFollowUp, parentQuestionIndex, key.referenceAnswer(), key.keyPoints(),
            key.scoringRubric(), sourceContext, fullScore, questionType, options, key.analysis(),
            sourcePageFrom, sourcePageTo, key.figureSummary(), key.source(), key.confidence(),
            key.requiresVisualGrading());
    }

    /** 交给学生端的题目副本：移除全部答案提示。 */
    public InterviewQuestionDTO forAttemptView() {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
            isFollowUp, parentQuestionIndex, null, List.of(), null, sourceContext, fullScore,
            questionType, options, null, sourcePageFrom, sourcePageTo, null, null, null, false);
    }

    /** 试卷概览的只读副本：除答案键外，再移除考点摘要和历史作答痕迹。 */
    public InterviewQuestionDTO forOverviewView() {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, null, null, null, null,
            isFollowUp, parentQuestionIndex, null, List.of(), null, sourceContext, fullScore,
            questionType, options, null, sourcePageFrom, sourcePageTo, null, null, null, false);
    }

    public boolean hasLowConfidenceAnswerKey() {
        return answerKeyConfidence != null && answerKeyConfidence < 60;
    }
}
