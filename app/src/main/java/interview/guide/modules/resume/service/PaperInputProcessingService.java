package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.resume.document.DocumentParser;
import interview.guide.modules.resume.document.ParsedDocument;
import interview.guide.modules.resume.document.ParsedPage;
import interview.guide.modules.resume.listener.PaperRecognitionStreamProducer;
import interview.guide.modules.resume.log.PaperFlowLog;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PaperInputFileEntity;
import interview.guide.modules.resume.model.PaperUploadResult;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.repository.PaperInputFileRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 试卷输入处理 worker：从对象存储下载原始文件，解析建页，再分流到识别或直切。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperInputProcessingService {

    private final ResumeRepository resumeRepository;
    private final PaperInputFileRepository inputFileRepository;
    private final PaperRecognitionService recognitionService;
    private final PaperRecognitionStreamProducer recognitionProducer;
    private final FileStorageService storageService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final List<DocumentParser> documentParsers;
    private final TransactionalExecutor transactionalExecutor;
    private final PaperUploadProperties uploadProperties;

    public void process(Long paperId, String attemptId) {
        heartbeat(paperId, attemptId);
        PracticePaperEntity paper = resumeRepository.findById(paperId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_NOT_FOUND));
        List<PaperInputFileEntity> inputs =
            inputFileRepository.findByPaperIdOrderBySeqAsc(paperId);
        if (inputs.isEmpty()) {
            throw new BusinessException(ErrorCode.PAPER_PARSE_FAILED, "没有可解析的原始文件");
        }
        // 恢复任务可能继承上次崩溃留下的半成品，先清空再从原始文件重建。
        recognitionService.cleanupInputArtifacts(paperId, List.of());

        List<String> uploadedKeys = new ArrayList<>();
        try {
            List<ParsedDocument> documents = parseAll(inputs, paperId, attemptId);
            heartbeat(paperId, attemptId);
            int totalPages = documents.stream().mapToInt(d -> d.pages().size()).sum();
            int visionPages = (int) documents.stream()
                .flatMap(d -> d.pages().stream())
                .filter(page -> page.source() == PageSource.IMAGE)
                .count();
            int textChars = documents.stream()
                .flatMap(d -> d.pages().stream())
                .filter(page -> page.source() == PageSource.TEXT)
                .mapToInt(page -> page.lines().stream().mapToInt(String::length).sum())
                .sum();
            validateLimits(visionPages, textChars);

            recognitionService.createPages(paper, documents, uploadedKeys);
            heartbeat(paperId, attemptId);
            String sourceType = recognitionService.resolveSourceType(documents);

            if (visionPages > 0) {
                recognitionService.chargePageQuota(paper.getOwnerId(), visionPages);
                transactionalExecutor.run(
                    () -> updatePaperAfterParse(paperId, sourceType, totalPages));
                transactionalExecutor.run(() -> markRecognitionPending(paperId));
                if (!recognitionProducer.sendRecognitionTask(paperId)) {
                    throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR, "识别任务投递失败，请稍后重试");
                }
                PaperFlowLog.event("PAPER_INPUT_ROUTED_VISION paperId={}, pages={}, visionPages={}",
                    paperId, totalPages, visionPages);
            } else {
                transactionalExecutor.run(
                    () -> updatePaperAfterParse(paperId, sourceType, totalPages));
                PaperUploadResult result =
                    recognitionService.enqueueDirectExtraction(paper, totalPages);
                if (result.recognitionStatus() == RecognitionStatus.FAILED) {
                    throw new BusinessException(
                        ErrorCode.PAPER_EXTRACTION_FAILED, result.message());
                }
                PaperFlowLog.event("PAPER_INPUT_ROUTED_DIRECT paperId={}, pages={}",
                    paperId, totalPages);
            }
            PaperFlowLog.event("PAPER_INPUT_PROCESS_END paperId={}, attemptId={}, status=SUCCESS",
                paperId, attemptId);
        } catch (RuntimeException e) {
            if (ownsLease(paperId, attemptId)) {
                recognitionService.cleanupInputArtifacts(paperId, uploadedKeys);
            }
            PaperFlowLog.error("PAPER_INPUT_PROCESS_END paperId={}, attemptId={}, status=FAILED",
                e, paperId, attemptId);
            throw e;
        }
    }

    private List<ParsedDocument> parseAll(List<PaperInputFileEntity> inputs,
                                          Long paperId, String attemptId) {
        List<ParsedDocument> documents = new ArrayList<>();
        for (PaperInputFileEntity input : inputs) {
            byte[] bytes = storageService.downloadFile(input.getStorageKey());
            String contentType =
                contentTypeDetectionService.detectContentType(bytes, input.getFilename());
            DocumentParser parser = documentParsers.stream()
                .filter(candidate -> candidate.supports(contentType, input.getFilename()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                    "不支持的文件类型：" + (contentType == null
                        ? input.getFilename() : contentType)));
            PaperFlowLog.event("PAPER_INPUT_FILE_PARSE_BEGIN paperId={}, seq={}, name={}, bytes={}",
                input.getPaperId(), input.getSeq(), input.getFilename(), bytes.length);
            ParsedDocument parsed = parser.parse(bytes, input.getFilename(), contentType);
            PaperFlowLog.event("PAPER_INPUT_FILE_PARSED paperId={}, seq={}, sourceType={}, pages={}, "
                    + "warnings={}",
                input.getPaperId(), input.getSeq(), parsed.sourceType(),
                parsed.pages().size(), parsed.warnings());
            documents.add(parsed);
            heartbeat(paperId, attemptId);
        }
        return documents;
    }

    private void heartbeat(Long paperId, String attemptId) {
        int updated = resumeRepository.heartbeatInputProcessing(
            paperId, attemptId, LocalDateTime.now());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析任务执行权已失效");
        }
    }

    private boolean ownsLease(Long paperId, String attemptId) {
        return resumeRepository.heartbeatInputProcessing(
            paperId, attemptId, LocalDateTime.now()) == 1;
    }

    private void validateLimits(int visionPages, int textChars) {
        if (visionPages > uploadProperties.getMaxVisionPages()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "单份试卷最多 " + uploadProperties.getMaxVisionPages() + " 页需要识图，当前共 "
                    + visionPages + " 页");
        }
        if (textChars > uploadProperties.getMaxTextChars()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "纯文字材料超过 " + uploadProperties.getMaxTextChars() + " 字符，请拆分后上传");
        }
    }

    private void updatePaperAfterParse(Long paperId, String sourceType, int totalPages) {
        PracticePaperEntity saved = resumeRepository.findById(paperId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_NOT_FOUND));
        saved.setSourceType(sourceType);
        saved.setPageCount(totalPages);
        resumeRepository.save(saved);
    }

    private void markRecognitionPending(Long paperId) {
        PracticePaperEntity saved = resumeRepository.findById(paperId).orElseThrow();
        saved.setRecognitionStatus(RecognitionStatus.PENDING);
        saved.setRecognitionError(null);
        saved.setRecognitionAttemptId(null);
        saved.setRecognitionUpdatedAt(LocalDateTime.now());
        resumeRepository.save(saved);
    }
}
