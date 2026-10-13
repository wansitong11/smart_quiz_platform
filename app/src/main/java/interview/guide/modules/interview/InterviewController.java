package interview.guide.modules.interview;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.result.Result;
import interview.guide.modules.interview.model.BatchSubmitAnswerRequest;
import interview.guide.modules.interview.model.BatchSubmitAnswerResponse;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.SessionListItemDTO;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.resume.service.PaperAttemptService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 面试控制器
 * 提供模拟面试相关的API接口
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "模拟面试", description = "面试会话创建、问答交互与报告生成")
public class InterviewController {
    
    private final InterviewSessionService sessionService;
    private final InterviewHistoryService historyService;
    private final InterviewPersistenceService persistenceService;
    private final PaperAttemptService paperAttemptService;
    
    /**
     * 列出所有面试会话（用于面试记录页）
     *
     * @param sourceTypes 可选：按来源过滤，逗号分隔（如 {@code QUIZ,QUIZ_WRONG}）。
     *                    不传时返回全部，保持对「知识库面试历史」等旧调用方的兼容。
     */
    @GetMapping("/api/interview/sessions")
    public Result<List<SessionListItemDTO>> listSessions(
            @RequestParam(required = false) String sourceTypes) {
        Set<String> filter = parseSourceTypes(sourceTypes);
        List<SessionListItemDTO> items = persistenceService.findAll().stream()
            .filter(entity -> filter == null || filter.contains(entity.getSourceType()))
            .map(SessionListItemDTO::from)
            .toList();
        return Result.success(items);
    }

    /** 逗号分隔的来源过滤；空串或 null 表示不过滤 */
    private Set<String> parseSourceTypes(String sourceTypes) {
        if (sourceTypes == null || sourceTypes.isBlank()) {
            return null;
        }
        Set<String> values = java.util.Arrays.stream(sourceTypes.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return values.isEmpty() ? null : values;
    }

    /**
     * 创建面试会话
     */
    @PostMapping("/api/interview/sessions")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<InterviewSessionDTO> createSession(@RequestBody CreateInterviewRequest request) {
        log.info("创建面试会话，题目数量: {}", request.questionCount());
        InterviewSessionDTO session = sessionService.createSession(request);
        return Result.success(session);
    }
    
    /**
     * 获取会话信息
     */
    @GetMapping("/api/interview/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        InterviewSessionDTO session = sessionService.getSession(sessionId);
        return Result.success(session);
    }
    
    /**
     * 获取当前问题
     */
    @GetMapping("/api/interview/sessions/{sessionId}/question")
    public Result<Map<String, Object>> getCurrentQuestion(@PathVariable String sessionId) {
        return Result.success(sessionService.getCurrentQuestionResponse(sessionId));
    }
    
    /**
     * 提交答案
     */
    @PostMapping("/api/interview/sessions/{sessionId}/answers")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("提交答案: 会话{}, 问题{}", sessionId, questionIndex);
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        SubmitAnswerResponse response = sessionService.submitAnswer(request);
        return Result.success(response);
    }
    
    /**
     * 整卷交卷（试卷 / 练习材料场景）
     * <p>
     * 一次提交整卷答案，后端批量落库并触发批改，适用于可跳题、可回改的做卷子流程。
     * 限流按 IP 维度收紧（整卷提交代价高），全局维度留出多用户并发的余量。
     * </p>
     */
    @PostMapping("/api/interview/sessions/{sessionId}/answers/batch")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<BatchSubmitAnswerResponse> submitWholePaper(
            @PathVariable String sessionId,
            @Valid @RequestBody BatchSubmitAnswerRequest request) {
        log.info("整卷交卷: 会话{}, 提交{}题", sessionId, request.answers().size());
        BatchSubmitAnswerResponse response = sessionService.submitWholePaper(sessionId, request.answers());
        return Result.success(response);
    }

    /**
     * 生成面试报告
     */
    @GetMapping("/api/interview/sessions/{sessionId}/report")
    public Result<InterviewReportDTO> getReport(@PathVariable String sessionId) {
        log.info("生成面试报告: {}", sessionId);
        InterviewReportDTO report = sessionService.generateReport(sessionId);
        return Result.success(report);
    }

    /**
     * 从这次试卷练习再开一次作答（报告页「再练一遍」）。
     * <p>
     * 上一次没交卷就返回那一次；已经交卷则基于同一份切题结果新开一次，
     * 作答完成后会新增一条练习记录。
     * </p>
     */
    @PostMapping("/api/interview/sessions/{sessionId}/attempts")
    public Result<InterviewSessionDTO> startAttempt(@PathVariable String sessionId) {
        log.info("再练一遍: 会话{}", sessionId);
        return Result.success(paperAttemptService.startAttemptFromSession(sessionId));
    }
    
    /**
     * 查找未完成的面试会话
     * GET /api/interview/sessions/unfinished/{resumeId}
     */
    @GetMapping("/api/interview/sessions/unfinished/{resumeId}")
    public Result<InterviewSessionDTO> findUnfinishedSession(@PathVariable Long resumeId) {
        return Result.success(sessionService.findUnfinishedSessionOrThrow(resumeId));
    }
    
    /**
     * 暂存答案（不进入下一题）
     */
    @PutMapping("/api/interview/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("暂存答案: 会话{}, 问题{}", sessionId, questionIndex);
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        sessionService.saveAnswer(request);
        return Result.success(null);
    }
    
    /**
     * 提前交卷
     */
    @PostMapping("/api/interview/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(@PathVariable String sessionId) {
        log.info("提前交卷: {}", sessionId);
        sessionService.completeInterview(sessionId);
        return Result.success(null);
    }
    
    /**
     * 获取面试会话详情
     * GET /api/interview/sessions/{sessionId}/details
     */
    @GetMapping("/api/interview/sessions/{sessionId}/details")
    public Result<InterviewDetailDTO> getInterviewDetail(@PathVariable String sessionId) {
        InterviewDetailDTO detail = historyService.getInterviewDetail(sessionId);
        return Result.success(detail);
    }
    
    /**
     * 导出面试报告为PDF
     */
    @GetMapping("/api/interview/sessions/{sessionId}/export")
    public ResponseEntity<byte[]> exportInterviewPdf(@PathVariable String sessionId) {
        try {
            byte[] pdfBytes = historyService.exportInterviewPdf(sessionId);
            String filename = URLEncoder.encode("模拟面试报告_" + sessionId + ".pdf", 
                StandardCharsets.UTF_8);
            
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
        } catch (Exception e) {
            // 越权 / 资源不存在由全局异常处理统一返回 404，不能吞成 500
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("导出PDF失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 删除面试会话
     */
    @DeleteMapping("/api/interview/sessions/{sessionId}")
    public Result<Void> deleteInterview(@PathVariable String sessionId) {
        log.info("删除面试会话: {}", sessionId);
        persistenceService.deleteSessionBySessionId(sessionId);
        return Result.success(null);
    }
}
