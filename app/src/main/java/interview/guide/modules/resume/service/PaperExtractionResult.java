package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperTree;

import java.util.List;
import java.util.Map;

/**
 * 试卷结构化切题结果。
 *
 * @param subjectId    识别出的学科（chinese / math / english）
 * @param paperTree    试卷层级树（卷 → 大题 → 小节 → 材料/题目）
 * @param questions     树叶子派生出的扁平题目列表（questionIndex 与树一致）
 * @param totalScore   卷面总分（各题分值之和）
 * @param categoryLabels 知识板块 key → 展示标签
 * @param warnings     解析告警（缺参考答案、分值为推断值、含图形无法识别等）
 */
public record PaperExtractionResult(
    String subjectId,
    PaperTree paperTree,
    List<InterviewQuestionDTO> questions,
    int totalScore,
    Map<String, String> categoryLabels,
    List<String> warnings
) {

    /**
     * 单道抽取出题目的原始形态。
     */
    public record ExtractedQuestion(
        int questionNo,
        String questionType,
        String question,
        List<QuestionOption> options,
        int fullScore,
        String categoryKey,
        String categoryLabel,
        List<String> knowledgePoints,
        String referenceAnswer,
        String scoringRubric,
        String analysis,
        String note
    ) {

        /**
         * 选项是否可用于选择题渲染与判分。
         */
        public boolean hasOptions() {
            return options != null && options.size() >= 2;
        }

        /**
         * 拼出用于作答与批改的完整题干：题号 + 题干 + 带标签的选项。
         */
        public String toQuestionText() {
            StringBuilder sb = new StringBuilder();
            if (questionNo > 0) {
                sb.append(questionNo).append(". ");
            }
            sb.append(question != null ? question.trim() : "");
            if (options != null && !options.isEmpty()) {
                for (QuestionOption option : options) {
                    if (option == null || option.text() == null || option.text().isBlank()) {
                        continue;
                    }
                    sb.append('\n').append(option.key()).append(". ").append(option.text().trim());
                }
            }
            return sb.toString();
        }
    }

    public boolean isEmpty() {
        return questions == null || questions.isEmpty();
    }
}
