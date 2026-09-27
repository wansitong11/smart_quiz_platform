package interview.guide.modules.interview.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 面试历史服务
 * 获取面试会话详情和导出面试报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewHistoryService {

    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final InterviewMapper interviewMapper;

    /**
     * 获取面试会话详情
     */
    @Transactional(readOnly = true)
    public InterviewDetailDTO getInterviewDetail(String sessionId) {
        Optional<PracticeSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        PracticeSessionEntity session = sessionOpt.get();

        // 试卷层级树是唯一题目数据源，扁平题目列表由它派生
        PaperTree paperTree = PaperTree.parse(objectMapper, session.getPaperJson());
        List<InterviewQuestionDTO> allQuestions = paperTree.flattenQuestions();
        List<Object> questions = new ArrayList<>(allQuestions);
        List<String> strengths = parseJson(session.getStrengthsJson(), new TypeReference<>() {});
        List<String> improvements = parseJson(session.getImprovementsJson(), new TypeReference<>() {});
        List<Object> referenceAnswers = parseJson(session.getReferenceAnswersJson(), new TypeReference<>() {});

        // 构建答案详情列表（包含所有题目，未回答的也要显示）
        List<InterviewDetailDTO.AnswerDetailDTO> answerList = buildAnswerDetailList(
            allQuestions,
            session.getAnswers()
        );

        // 按小节汇总得分
        List<InterviewDetailDTO.SectionScoreDTO> sectionScores =
            buildSectionScores(paperTree, session.getAnswers());

        // 使用 MapStruct 组装最终 DTO
        return interviewMapper.toDetailDTO(
            session,
            questions,
            strengths,
            improvements,
            referenceAnswers,
            answerList,
            paperTree,
            sectionScores
        );
    }

    /**
     * 按最近的小节（SECTION）汇总得分：组内折算分之和 / 组满分。
     */
    private List<InterviewDetailDTO.SectionScoreDTO> buildSectionScores(
        PaperTree tree, List<PracticeAnswerEntity> answers) {
        java.util.Map<Integer, PracticeAnswerEntity> answerByIndex = new java.util.HashMap<>();
        for (PracticeAnswerEntity answer : answers) {
            if (answer.getQuestionIndex() != null) {
                answerByIndex.putIfAbsent(answer.getQuestionIndex(), answer);
            }
        }

        java.util.Map<String, int[]> accumulator = new java.util.LinkedHashMap<>();
        java.util.Map<String, PaperNode> sectionOf = new java.util.LinkedHashMap<>();
        for (PaperNode questionNode : tree.questionNodes()) {
            if (questionNode.questionIndex() == null) {
                continue;
            }
            PaperNode section = tree.nearestSection(questionNode.questionIndex()).orElse(null);
            String key = section != null ? section.id() : "__none__";
            sectionOf.putIfAbsent(key, section);

            int fullScore = questionNode.meta() != null && questionNode.meta().fullScore() != null
                ? questionNode.meta().fullScore() : 0;
            PracticeAnswerEntity answer = answerByIndex.get(questionNode.questionIndex());
            int rate = answer != null && answer.getScore() != null ? answer.getScore() : 0;
            int score = fullScore > 0 ? (int) Math.round(rate * fullScore / 100.0) : 0;

            int[] acc = accumulator.computeIfAbsent(key, ignored -> new int[3]);
            acc[0] += 1;
            acc[1] += fullScore;
            acc[2] += score;
        }

        List<InterviewDetailDTO.SectionScoreDTO> result = new ArrayList<>();
        for (java.util.Map.Entry<String, int[]> entry : accumulator.entrySet()) {
            PaperNode section = sectionOf.get(entry.getKey());
            int[] acc = entry.getValue();
            int rate = acc[1] > 0 ? (int) Math.round(acc[2] * 100.0 / acc[1]) : 0;
            result.add(new InterviewDetailDTO.SectionScoreDTO(
                section != null && section.title() != null ? section.title() : "未分节",
                section != null ? section.subtitle() : null,
                section != null ? section.depth() : 0,
                acc[0],
                acc[1],
                acc[2],
                rate
            ));
        }
        return result;
    }

    /**
     * 构建答案详情列表（包含所有题目）
     * 对于用户已回答的题目使用答案数据，对于未回答的题目构建空答案
     */
    private List<InterviewDetailDTO.AnswerDetailDTO> buildAnswerDetailList(
        List<InterviewQuestionDTO> allQuestions,
        List<PracticeAnswerEntity> answers
    ) {
        if (allQuestions == null || allQuestions.isEmpty()) {
            // 如果没有题目数据，回退到仅显示已回答的题目
            return interviewMapper.toAnswerDetailDTOList(answers, this::extractKeyPoints);
        }

        // 将答案按 questionIndex 索引
        java.util.Map<Integer, PracticeAnswerEntity> answerMap = answers.stream()
            .collect(java.util.stream.Collectors.toMap(
                PracticeAnswerEntity::getQuestionIndex,
                a -> a,
                (a1, a2) -> a1  // 如果有重复，取第一个
            ));

        // 遍历所有题目，构建完整的答案详情列表
        return allQuestions.stream()
            .map(question -> {
                PracticeAnswerEntity answer = answerMap.get(question.questionIndex());
                if (answer != null) {
                    // 用户已回答，使用答案数据
                    return interviewMapper.toAnswerDetailDTO(answer, extractKeyPoints(answer));
                } else {
                    // 用户未回答，构建空答案
                    return new InterviewDetailDTO.AnswerDetailDTO(
                        question.questionIndex(),
                        question.question(),
                        question.category(),
                        null,  // userAnswer
                        question.score() != null ? question.score() : 0,  // score
                        question.feedback(),  // feedback
                        null,  // referenceAnswer
                        null,  // keyPoints
                        null   // answeredAt
                    );
                }
            })
            .toList();
    }

    /**
     * 从 JSON 提取 keyPoints
     */
    private List<String> extractKeyPoints(PracticeAnswerEntity answer) {
        return parseJson(answer.getKeyPointsJson(), new TypeReference<>() {});
    }

    /**
     * 通用 JSON 解析方法
     */
    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JacksonException e) {
            log.error("解析 JSON 失败", e);
            return null;
        }
    }

    /**
     * 导出面试报告为PDF
     */
    public byte[] exportInterviewPdf(String sessionId) {
        Optional<PracticeSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        PracticeSessionEntity session = sessionOpt.get();
        try {
            return pdfExportService.exportInterviewReport(session);
        } catch (Exception e) {
            log.error("导出PDF失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: " + e.getMessage());
        }
    }
}
