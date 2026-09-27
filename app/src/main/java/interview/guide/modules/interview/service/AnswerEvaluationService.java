package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.CategoryScore;
import interview.guide.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import interview.guide.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.skill.InterviewSkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文字面试答案评估服务
 * 职责：DTO 适配器，将 InterviewQuestionDTO 转为通用 QaRecord，调用 UnifiedEvaluationService
 */
@Service
public class AnswerEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSkillService skillService;
    private final ObjectiveAnswerGrader objectiveAnswerGrader;

    public AnswerEvaluationService(UnifiedEvaluationService unifiedEvaluationService,
                                   InterviewPersistenceService persistenceService,
                                   InterviewSkillService skillService,
                                   ObjectiveAnswerGrader objectiveAnswerGrader) {
        this.unifiedEvaluationService = unifiedEvaluationService;
        this.persistenceService = persistenceService;
        this.skillService = skillService;
        this.objectiveAnswerGrader = objectiveAnswerGrader;
    }

    /**
     * 评估整份作答并生成报告。
     * <p>
     * 先分流：选择题与可规范比对的填空题走确定性判分（不调用模型），
     * 其余题目才交给统一评估引擎。两部分结果合并成同一份报告，
     * 因此纯客观题试卷不会产生任何模型调用。
     * </p>
     */
    public InterviewReportDTO evaluateInterview(ChatClient chatClient, String sessionId, String resumeText,
                                                 List<InterviewQuestionDTO> questions) {
        log.info("开始评估面试: {}, 共{}题", sessionId, questions.size());

        try {
            Map<Integer, ObjectiveAnswerGrader.Grade> objectiveGrades = new LinkedHashMap<>();
            List<InterviewQuestionDTO> subjectiveQuestions = new ArrayList<>();
            for (InterviewQuestionDTO question : questions) {
                objectiveAnswerGrader.grade(question).ifPresentOrElse(
                    grade -> objectiveGrades.put(question.questionIndex(), grade),
                    () -> subjectiveQuestions.add(question)
                );
            }

            EvaluationReport llmReport = null;
            if (!subjectiveQuestions.isEmpty()) {
                PaperTree paperTree = persistenceService.findPaperTree(sessionId).orElse(null);
                String referenceContext = buildQuestionReferenceContext(questions, paperTree);
                if (referenceContext.isBlank()) {
                    referenceContext = skillService.buildEvaluationReferenceSectionSafe(
                        persistenceService.findBySessionId(sessionId)
                            .map(s -> s.getSkillId())
                            .orElse(null)
                    );
                }
                List<QaRecord> qaRecords = subjectiveQuestions.stream()
                    .map(q -> new QaRecord(q.questionIndex(), q.question(), q.category(), q.userAnswer(),
                        q.isFollowUp(), q.parentQuestionIndex()))
                    .toList();
                llmReport = unifiedEvaluationService.evaluate(
                    chatClient, sessionId, qaRecords, resumeText, referenceContext
                );
            } else {
                log.info("本次全部为客观题，跳过模型批改: sessionId={}, 题数={}", sessionId, questions.size());
            }
            log.info("批改分流结果: sessionId={}, 客观题={}, 主观题={}",
                sessionId, objectiveGrades.size(), subjectiveQuestions.size());

            return withQuestionReferences(
                mergeReports(sessionId, questions, objectiveGrades, llmReport), questions);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("面试评估失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试评估失败");
        }
    }

    /**
     * 合并客观题判分与模型批改结果，产出统一报告。
     */
    private InterviewReportDTO mergeReports(String sessionId,
                                            List<InterviewQuestionDTO> questions,
                                            Map<Integer, ObjectiveAnswerGrader.Grade> objectiveGrades,
                                            EvaluationReport llmReport) {
        Map<Integer, QuestionEvaluation> details = new LinkedHashMap<>();
        if (llmReport != null) {
            for (EvaluationReport.QuestionEvaluation qe : llmReport.questionDetails()) {
                details.put(qe.questionIndex(), new QuestionEvaluation(
                    qe.questionIndex(), qe.question(), qe.category(), qe.userAnswer(),
                    qe.score(), qe.feedback(), null, null));
            }
        }
        for (InterviewQuestionDTO question : questions) {
            ObjectiveAnswerGrader.Grade grade = objectiveGrades.get(question.questionIndex());
            if (grade == null) {
                continue;
            }
            details.put(question.questionIndex(), new QuestionEvaluation(
                question.questionIndex(), question.question(), question.category(), question.userAnswer(),
                grade.scoreRate(), grade.feedback(), null, null));
        }
        // 两道分流都没覆盖到的题目（模型漏评）给 0 分兜底，避免报告缺题
        for (InterviewQuestionDTO question : questions) {
            details.putIfAbsent(question.questionIndex(), new QuestionEvaluation(
                question.questionIndex(), question.question(), question.category(), question.userAnswer(),
                0, "未获得批改结果，请重新批改。", null, null));
        }

        List<QuestionEvaluation> ordered = details.values().stream()
            .sorted(Comparator.comparingInt(QuestionEvaluation::questionIndex))
            .toList();

        List<InterviewReportDTO.ReferenceAnswer> references = buildReferenceAnswers(questions, llmReport);
        List<CategoryScore> categoryScores = buildCategoryScores(questions, ordered);
        int overallScore = weightedScoreRate(questions, ordered);
        boolean objectiveOnly = llmReport == null;

        String overallFeedback = objectiveOnly
            ? buildObjectiveFeedback(ordered, overallScore)
            : llmReport.overallFeedback();
        List<String> strengths = objectiveOnly
            ? buildCategoryHighlights(categoryScores, true)
            : llmReport.strengths();
        List<String> improvements = objectiveOnly
            ? buildCategoryHighlights(categoryScores, false)
            : llmReport.improvements();

        return new InterviewReportDTO(
            sessionId,
            questions.size(),
            overallScore,
            0,
            0,
            categoryScores,
            ordered,
            overallFeedback,
            strengths == null ? List.of() : strengths,
            improvements == null ? List.of() : improvements,
            references
        );
    }

    /**
     * 参考答案：优先用模型给出的深度解析，客观题用切题时落库的参考答案与要点。
     */
    private List<InterviewReportDTO.ReferenceAnswer> buildReferenceAnswers(
        List<InterviewQuestionDTO> questions, EvaluationReport llmReport) {
        Map<Integer, InterviewReportDTO.ReferenceAnswer> byIndex = new LinkedHashMap<>();
        if (llmReport != null) {
            for (EvaluationReport.ReferenceAnswer ra : llmReport.referenceAnswers()) {
                byIndex.put(ra.questionIndex(), new ReferenceAnswer(
                    ra.questionIndex(), ra.question(), ra.referenceAnswer(), ra.keyPoints()));
            }
        }
        for (InterviewQuestionDTO question : questions) {
            if (!hasQuestionReference(question)) {
                continue;
            }
            byIndex.putIfAbsent(question.questionIndex(), new ReferenceAnswer(
                question.questionIndex(),
                question.question(),
                hasText(question.referenceAnswer()) ? question.referenceAnswer() : null,
                question.keyPoints() != null ? question.keyPoints() : List.of()));
        }
        return byIndex.values().stream()
            .sorted(Comparator.comparingInt(InterviewReportDTO.ReferenceAnswer::questionIndex))
            .toList();
    }

    /**
     * 按知识板块汇总平均得分率（客观题与主观题一并统计）。
     */
    private List<CategoryScore> buildCategoryScores(List<InterviewQuestionDTO> questions,
                                                    List<QuestionEvaluation> details) {
        Map<Integer, String> categoryOf = new LinkedHashMap<>();
        for (InterviewQuestionDTO question : questions) {
            categoryOf.put(question.questionIndex(),
                hasText(question.category()) ? question.category() : "未分类");
        }
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (QuestionEvaluation detail : details) {
            String category = categoryOf.getOrDefault(detail.questionIndex(), "未分类");
            int[] acc = stats.computeIfAbsent(category, key -> new int[2]);
            acc[0] += detail.score();
            acc[1] += 1;
        }
        return stats.entrySet().stream()
            .map(entry -> new CategoryScore(entry.getKey(),
                entry.getValue()[1] == 0 ? 0 : Math.round((float) entry.getValue()[0] / entry.getValue()[1]),
                entry.getValue()[1]))
            .toList();
    }

    /**
     * 综合得分率：有分值时按分值加权，无分值时取算术平均。
     */
    private int weightedScoreRate(List<InterviewQuestionDTO> questions,
                                  List<QuestionEvaluation> details) {
        Map<Integer, Integer> fullScoreOf = new LinkedHashMap<>();
        for (InterviewQuestionDTO question : questions) {
            Integer fullScore = question.fullScore();
            fullScoreOf.put(question.questionIndex(), fullScore != null && fullScore > 0 ? fullScore : 0);
        }
        boolean hasFullScore = fullScoreOf.values().stream().anyMatch(score -> score > 0);
        double weighted = 0;
        double weight = 0;
        for (QuestionEvaluation detail : details) {
            int fullScore = fullScoreOf.getOrDefault(detail.questionIndex(), 0);
            double w = hasFullScore ? (fullScore > 0 ? fullScore : 0) : 1;
            if (w == 0) {
                continue;
            }
            weighted += detail.score() * w;
            weight += w;
        }
        return weight == 0 ? 0 : (int) Math.round(weighted / weight);
    }

    private String buildObjectiveFeedback(List<QuestionEvaluation> details, int overallScore) {
        long fullMark = details.stream().filter(detail -> detail.score() >= 100).count();
        return "本次共 " + details.size() + " 道客观题，全部由系统按标准答案判分：全对 "
            + fullMark + " 题，整体得分率 " + overallScore + "%。";
    }

    private List<String> buildCategoryHighlights(List<CategoryScore> categoryScores, boolean positive) {
        return categoryScores.stream()
            .filter(score -> positive ? score.score() >= 80 : score.score() < 60)
            .sorted((a, b) -> positive
                ? Integer.compare(b.score(), a.score())
                : Integer.compare(a.score(), b.score()))
            .limit(3)
            .map(score -> (positive ? "掌握较好：" : "需要加强：") + score.category()
                + "（" + score.score() + "%，共 " + score.questionCount() + " 题）")
            .toList();
    }

    private InterviewReportDTO toInterviewReportDTO(EvaluationReport report) {
        return new InterviewReportDTO(
            report.sessionId(),
            report.totalQuestions(),
            report.overallScore(),
            0,
            0,
            report.categoryScores().stream()
                .map(cs -> new CategoryScore(cs.category(), cs.score(), cs.questionCount()))
                .toList(),
            report.questionDetails().stream()
                .map(qe -> new QuestionEvaluation(qe.questionIndex(), qe.question(), qe.category(),
                    qe.userAnswer(), qe.score(), qe.feedback(), null, null))
                .toList(),
            report.overallFeedback(),
            report.strengths(),
            report.improvements(),
            report.referenceAnswers().stream()
                .map(ra -> new ReferenceAnswer(ra.questionIndex(), ra.question(),
                    ra.referenceAnswer(), ra.keyPoints()))
                .toList()
        );
    }

    private InterviewReportDTO withQuestionReferences(InterviewReportDTO report,
                                                      List<InterviewQuestionDTO> questions) {
        List<InterviewReportDTO.ReferenceAnswer> references = report.referenceAnswers().stream()
            .map(reference -> {
                InterviewQuestionDTO question = questions.stream()
                    .filter(item -> item.questionIndex() == reference.questionIndex())
                    .findFirst()
                    .orElse(null);
                if (question == null || question.referenceAnswer() == null
                    || question.referenceAnswer().isBlank()) {
                    return reference;
                }
                return new InterviewReportDTO.ReferenceAnswer(
                    reference.questionIndex(),
                    reference.question(),
                    question.referenceAnswer(),
                    question.keyPoints() != null ? question.keyPoints() : List.of()
                );
            })
            .toList();
        return new InterviewReportDTO(
            report.sessionId(),
            report.totalQuestions(),
            report.overallScore(),
            sumFullScore(questions),
            sumPaperScore(report, questions),
            report.categoryScores(),
            withFullScores(report.questionDetails(), questions),
            report.overallFeedback(),
            report.strengths(),
            report.improvements(),
            references
        );
    }

    /**
     * 卷面总分：各题分值之和。普通自动出题场景题目没有分值，返回 0。
     */
    private int sumFullScore(List<InterviewQuestionDTO> questions) {
        return questions.stream()
            .map(InterviewQuestionDTO::fullScore)
            .filter(Objects::nonNull)
            .filter(score -> score > 0)
            .mapToInt(Integer::intValue)
            .sum();
    }

    /**
     * 卷面得分：每题的得分率按该题分值折算后求和（得分率 × 分值 / 100，四舍五入）。
     */
    private int sumPaperScore(InterviewReportDTO report, List<InterviewQuestionDTO> questions) {
        int total = 0;
        for (InterviewReportDTO.QuestionEvaluation evaluation : report.questionDetails()) {
            total += paperScoreOf(evaluation.score(), fullScoreOf(questions, evaluation.questionIndex()));
        }
        return total;
    }

    /**
     * 给每题补上分值与折算后的卷面得分。
     */
    private List<InterviewReportDTO.QuestionEvaluation> withFullScores(
        List<InterviewReportDTO.QuestionEvaluation> evaluations,
        List<InterviewQuestionDTO> questions) {
        return evaluations.stream()
            .map(evaluation -> {
                Integer fullScore = fullScoreOf(questions, evaluation.questionIndex());
                return new InterviewReportDTO.QuestionEvaluation(
                    evaluation.questionIndex(),
                    evaluation.question(),
                    evaluation.category(),
                    evaluation.userAnswer(),
                    evaluation.score(),
                    evaluation.feedback(),
                    fullScore,
                    fullScore != null ? paperScoreOf(evaluation.score(), fullScore) : null
                );
            })
            .toList();
    }

    private Integer fullScoreOf(List<InterviewQuestionDTO> questions, int questionIndex) {
        return questions.stream()
            .filter(item -> item.questionIndex() == questionIndex)
            .map(InterviewQuestionDTO::fullScore)
            .filter(score -> score != null && score > 0)
            .findFirst()
            .orElse(null);
    }

    private int paperScoreOf(int scoreRate, Integer fullScore) {
        if (fullScore == null || fullScore <= 0) {
            return 0;
        }
        return (int) Math.round(Math.max(0, Math.min(100, scoreRate)) * fullScore / 100.0);
    }

    private String buildQuestionReferenceContext(List<InterviewQuestionDTO> questions) {
        return buildQuestionReferenceContext(questions, null);
    }

    /**
     * 评分依据：每个小节的材料原文只拼一次，题目前的「所属小节」帮助模型定位上下文。
     */
    private String buildQuestionReferenceContext(List<InterviewQuestionDTO> questions, PaperTree paperTree) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> emittedContexts = new java.util.LinkedHashSet<>();
        for (InterviewQuestionDTO question : questions) {
            if (paperTree != null) {
                String context = paperTree.contextOf(question.questionIndex());
                if (!context.isBlank() && emittedContexts.add(context)) {
                    sb.append(context).append('\n');
                }
            }
            if (!hasQuestionReference(question)) {
                continue;
            }
            sb.append("问题").append(question.questionIndex() + 1);
            if (paperTree != null) {
                paperTree.nearestSection(question.questionIndex())
                    .map(node -> node.title() != null ? node.title() : "")
                    .filter(title -> !title.isBlank())
                    .ifPresent(title -> sb.append("（所属小节：").append(title).append('）'));
            }
            sb.append(": ").append(question.question()).append('\n');
            appendIfPresent(sb, "参考答案", question.referenceAnswer());
            if (question.keyPoints() != null && !question.keyPoints().isEmpty()) {
                sb.append("评分要点: ").append(String.join("；", question.keyPoints())).append('\n');
            }
            appendIfPresent(sb, "评分规则", question.scoringRubric());
            sb.append('\n');
        }
        return sb.toString();
    }

    private boolean hasQuestionReference(InterviewQuestionDTO question) {
        return hasText(question.referenceAnswer())
            || hasText(question.scoringRubric())
            || (question.keyPoints() != null && !question.keyPoints().isEmpty());
    }

    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (hasText(value)) {
            sb.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
