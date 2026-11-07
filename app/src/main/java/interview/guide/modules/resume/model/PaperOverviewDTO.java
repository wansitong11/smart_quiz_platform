package interview.guide.modules.resume.model;

import interview.guide.modules.interview.model.PaperTree;

/**
 * 试卷概览：只读预览整卷结构，题目已剥离答案键信息。
 */
public record PaperOverviewDTO(
    Long paperId,
    String subjectId,
    int totalScore,
    int questionCount,
    PaperTree paperTree
) {
}
