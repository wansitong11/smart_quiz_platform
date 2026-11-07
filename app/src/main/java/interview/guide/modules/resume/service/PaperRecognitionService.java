package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.resume.listener.PaperExtractionStreamProducer;
import interview.guide.modules.resume.listener.PaperInputStreamProducer;
import interview.guide.modules.resume.listener.PaperRecognitionStreamProducer;
import interview.guide.modules.resume.document.AssetKind;
import interview.guide.modules.resume.document.AssetRole;
import interview.guide.modules.resume.document.DocAsset;
import interview.guide.modules.resume.document.DocumentParser;
import interview.guide.modules.resume.document.ParsedDocument;
import interview.guide.modules.resume.document.ParsedPage;
import interview.guide.modules.resume.model.PageRecognition;
import interview.guide.modules.resume.model.PageAssetGeometry;
import interview.guide.modules.resume.model.PageRecognitionDiagnostic;
import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.model.PaperInputFileEntity;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.model.PaperRecognitionDTO;
import interview.guide.modules.resume.model.PaperUploadResult;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.ProcessingPath;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.model.StemFigureVerdict;
import interview.guide.modules.resume.model.StructurePreviewDTO;
import interview.guide.modules.resume.log.PaperFlowLog;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.PaperInputFileRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.PageTextMerger.MergeResult;
import interview.guide.modules.resume.service.PaperPageRecognitionProcessor.RecognizedPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图片 / 扫描件试卷识别编排。
 * <p>
 * 职责只有一条：把"上传的页"变成"可切题的整卷文本"。
 * 识别完不直接出题——用户先预览校对，确认后才投递现有 {@code paper:extract} 切题任务。
 * </p>
 */
@Slf4j
@Service
public class PaperRecognitionService {

  public static final int MAX_FILES = 10;
  public static final long MAX_IMAGE_SIZE = 50L * 1024 * 1024;
  public static final long MAX_DOCUMENT_SIZE = 50L * 1024 * 1024;
  static final int MAX_PAGE_ATTEMPTS = 3;
  private static final int DAILY_PAGE_QUOTA = 50;
  private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final String QUOTA_KEY_PREFIX = "paper:recognition:pages:";

  private static final Map<String, String> IMAGE_CONTENT_TYPES = Map.of(
      "image/jpeg", "jpg",
      "image/png", "png"
  );

  private final OwnerScope ownerScope;
  private final ResumeRepository resumeRepository;
  private final PaperPageRepository pageRepository;
  private final FileStorageService storageService;
  private final FileHashService fileHashService;
  private final ContentTypeDetectionService contentTypeDetectionService;
  private final PdfPageRenderer pdfPageRenderer;
  private final PageImageNormalizer imageNormalizer;
  private final PaperTableRegionDetector tableRegionDetector;
  private final PaperFigureCropResolver figureCropResolver;
  private final PaperVisualBlockResolver visualBlockResolver;
  private final PaperMissingStemAssetResolver missingStemAssetResolver;
  private final PaperLayoutAssetResolver layoutAssetResolver;
  private final PageRecognitionValidator recognitionValidator;
  private final PageTextMerger pageTextMerger;
  private final List<DocumentParser> documentParsers;
  private final PaperAssetService assetService;
  private final PaperContentGuard paperContentGuard;
  private final PaperStructureService structureService;
  private final LlmProviderRegistry llmProviderRegistry;
  private final PaperPageRecognitionProcessor pageRecognitionProcessor;
  private final PaperPageVisionRecognizer pageVisionRecognizer;
  private final PaperExtractionStreamProducer extractionProducer;
  private final PaperRecognitionStreamProducer recognitionProducer;
  private final PaperInputStreamProducer paperInputStreamProducer;
  private final PaperInputFileRepository paperInputFileRepository;
  private final RedisService redisService;
  private final TransactionalExecutor transactionalExecutor;
  private final PaperUploadProperties uploadProperties;
  private final ObjectMapper objectMapper;

  public PaperRecognitionService(
      OwnerScope ownerScope,
      ResumeRepository resumeRepository,
      PaperPageRepository pageRepository,
      FileStorageService storageService,
      FileHashService fileHashService,
      ContentTypeDetectionService contentTypeDetectionService,
      PdfPageRenderer pdfPageRenderer,
      PageImageNormalizer imageNormalizer,
      PaperTableRegionDetector tableRegionDetector,
      PaperFigureCropResolver figureCropResolver,
      PaperVisualBlockResolver visualBlockResolver,
      PaperMissingStemAssetResolver missingStemAssetResolver,
      PaperLayoutAssetResolver layoutAssetResolver,
      PageRecognitionValidator recognitionValidator,
      PageTextMerger pageTextMerger,
      List<DocumentParser> documentParsers,
      PaperAssetService assetService,
      PaperContentGuard paperContentGuard,
      PaperStructureService structureService,
      LlmProviderRegistry llmProviderRegistry,
      PaperPageRecognitionProcessor pageRecognitionProcessor,
      PaperPageVisionRecognizer pageVisionRecognizer,
      PaperExtractionStreamProducer extractionProducer,
      PaperRecognitionStreamProducer recognitionProducer,
      PaperInputStreamProducer paperInputStreamProducer,
      PaperInputFileRepository paperInputFileRepository,
      RedisService redisService,
      TransactionalExecutor transactionalExecutor,
      PaperUploadProperties uploadProperties,
      ObjectMapper objectMapper
  ) {
    this.ownerScope = ownerScope;
    this.resumeRepository = resumeRepository;
    this.pageRepository = pageRepository;
    this.storageService = storageService;
    this.fileHashService = fileHashService;
    this.contentTypeDetectionService = contentTypeDetectionService;
    this.pdfPageRenderer = pdfPageRenderer;
    this.imageNormalizer = imageNormalizer;
    this.tableRegionDetector = tableRegionDetector;
    this.figureCropResolver = figureCropResolver;
    this.visualBlockResolver = visualBlockResolver;
    this.missingStemAssetResolver = missingStemAssetResolver;
    this.layoutAssetResolver = layoutAssetResolver;
    this.recognitionValidator = recognitionValidator;
    this.pageTextMerger = pageTextMerger;
    this.documentParsers = documentParsers;
    this.assetService = assetService;
    this.paperContentGuard = paperContentGuard;
    this.structureService = structureService;
    this.llmProviderRegistry = llmProviderRegistry;
    this.pageRecognitionProcessor = pageRecognitionProcessor;
    this.pageVisionRecognizer = pageVisionRecognizer;
    this.extractionProducer = extractionProducer;
    this.recognitionProducer = recognitionProducer;
    this.paperInputStreamProducer = paperInputStreamProducer;
    this.paperInputFileRepository = paperInputFileRepository;
    this.redisService = redisService;
    this.transactionalExecutor = transactionalExecutor;
    this.uploadProperties = uploadProperties;
    this.objectMapper = objectMapper;
  }

  // ==================== 上传 ====================

  /**
   * 上传 1~10 个 PDF / DOCX / DOC / TXT / MD / JPG / PNG，按顺序合并为同一份试卷。
   * <p>
   * 全部页面都有可信文字层时直接投递切题任务；任意一页需要识图时走逐页识别与预览校对。
   * </p>
   */
  public PaperUploadResult upload(List<MultipartFile> files) {
    long startNanos = System.nanoTime();
    try (PaperFlowLog.Scope ignored = PaperFlowLog.open("PAPER_UPLOAD")) {
      PaperFlowLog.event("PAPER_UPLOAD_BEGIN files={}", fileSummaries(files));
      try {
        PaperUploadResult result = doUpload(files);
        PaperFlowLog.event("PAPER_UPLOAD_END status=SUCCESS, paperId={}, pages={}, path={}, "
                + "duplicate={}, message={}, durationMs={}",
            result.paperId(), result.pageCount(), result.processingPath(), result.duplicate(),
            result.message(), elapsedMs(startNanos));
        return result;
      } catch (RuntimeException e) {
        PaperFlowLog.error("PAPER_UPLOAD_END status=FAILED, durationMs={}",
            e, elapsedMs(startNanos));
        throw e;
      }
    }
  }

  private PaperUploadResult doUpload(List<MultipartFile> files) {
    List<MultipartFile> validFiles = validateFiles(files);

    List<String> hashes = new ArrayList<>();
    List<PreparedUpload> prepared = new ArrayList<>();
    for (MultipartFile file : validFiles) {
      byte[] bytes = readBytes(file);
      String contentType = contentTypeDetectionService.detectContentType(bytes, file.getOriginalFilename());
      hashes.add(fileHashService.calculateHash(bytes));
      prepared.add(new PreparedUpload(
          file.getOriginalFilename(), bytes, contentType, file.getSize()));
    }

    String fileHash = hashes.size() == 1
        ? hashes.get(0)
        : fileHashService.calculateHash(String.join(":", hashes).getBytes(StandardCharsets.UTF_8));

    Optional<PracticePaperEntity> existing =
        resumeRepository.findByFileHashScoped(fileHash, ownerScope.effectiveOwnerId());
    if (existing.isPresent()) {
      PracticePaperEntity paper = existing.get();
      PaperFlowLog.bindPaperId(paper.getId());
      log.info("检测到重复试卷，直接返回现有记录: paperId={}", paper.getId());
      ProcessingPath path = paper.getInputStatus() != AsyncTaskStatus.COMPLETED
          ? ProcessingPath.INPUT_PENDING
          : resolveProcessingPath(paper);
      return new PaperUploadResult(paper.getId(), safePageCount(paper),
          paper.getRecognitionStatus(), path, true,
          "检测到相同文件，已返回现有试卷");
    }

    PracticePaperEntity paper = transactionalExecutor.call(() -> {
      PracticePaperEntity entity = new PracticePaperEntity();
      entity.setFileHash(fileHash);
      entity.setOwnerId(ownerScope.currentUserId());
      entity.setOriginalFilename(validFiles.size() == 1
          ? validFiles.get(0).getOriginalFilename()
          : "多页试卷（" + validFiles.size() + " 个文件）");
      entity.setSourceType("PDF");
      entity.setFileSize(validFiles.stream().mapToLong(MultipartFile::getSize).sum());
      entity.setContentType(validFiles.get(0).getContentType());
      entity.setResumeText(null);
      entity.setPageCount(0);
      entity.setRecognitionStatus(RecognitionStatus.NONE);
      entity.setRecognitionUpdatedAt(LocalDateTime.now());
      entity.setAnalysisStatus(AsyncTaskStatus.PENDING);
      entity.setAnalysisUpdatedAt(LocalDateTime.now());
      entity.setInputStatus(AsyncTaskStatus.PENDING);
      entity.setInputUpdatedAt(LocalDateTime.now());
      return resumeRepository.save(entity);
    });
    PaperFlowLog.bindPaperId(paper.getId());

    List<String> originalKeys = new ArrayList<>();
    try {
      List<PaperInputFileEntity> inputFiles = new ArrayList<>();
      int seq = 0;
      for (PreparedUpload item : prepared) {
        String storageKey = storageService.uploadBytes(
            item.bytes(), item.filename(), item.contentType(), "paper-input");
        originalKeys.add(storageKey);
        PaperInputFileEntity input = new PaperInputFileEntity();
        input.setPaperId(paper.getId());
        input.setSeq(++seq);
        input.setStorageKey(storageKey);
        input.setFilename(item.filename());
        input.setContentType(item.contentType());
        input.setSizeBytes(item.size());
        inputFiles.add(input);
      }
      transactionalExecutor.run(() -> paperInputFileRepository.saveAll(inputFiles));
    } catch (RuntimeException e) {
      cleanupOriginalFiles(originalKeys);
      abandonUnusableInputRegistration(paper.getId());
      PaperFlowLog.error("PAPER_INPUT_REGISTER_FAILED paperId={}", e, paper.getId());
      throw e;
    }

    boolean sent = paperInputStreamProducer.sendInputTask(paper.getId());
    PaperFlowLog.event("PAPER_INPUT_ENQUEUED paperId={}, files={}, sent={}",
        paper.getId(), originalKeys.size(), sent);
    if (!sent) {
      return new PaperUploadResult(paper.getId(), 0, RecognitionStatus.NONE,
          ProcessingPath.INPUT_PENDING, false,
          "文件已接收，但解析任务入队失败，请稍后重试");
    }
    return new PaperUploadResult(paper.getId(), 0, RecognitionStatus.NONE,
        ProcessingPath.INPUT_PENDING, false, "文件已接收，正在解析");
  }

  /**
   * 输入处理失败后的手动重试入口：原始文件已持久化，可直接重建页面。
   */
  public void retryInputProcessing(Long paperId) {
    PracticePaperEntity paper = requirePaper(paperId);
    if (paper.getInputStatus() == AsyncTaskStatus.PENDING
        || paper.getInputStatus() == AsyncTaskStatus.PROCESSING) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "文件正在解析，请稍后");
    }
    if (paper.getInputStatus() != AsyncTaskStatus.FAILED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前状态不允许重新解析");
    }
    if (paperInputFileRepository.findByPaperIdOrderBySeqAsc(paperId).isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "原始文件缺失，请重新上传");
    }
    resumeRepository.resetInputRecoveryCount(paperId);
    boolean reset = transactionalExecutor.call(() ->
        resumeRepository.retryInputFromFailed(paperId, LocalDateTime.now()) == 1);
    if (!reset) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前状态不允许重新解析");
    }
    if (!paperInputStreamProducer.sendInputTask(paperId)) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析任务投递失败，请稍后重试");
    }
    PaperFlowLog.event("PAPER_INPUT_RETRY_ENQUEUED paperId={}", paperId);
  }

  private void cleanupOriginalFiles(List<String> storageKeys) {
    for (String storageKey : storageKeys) {
      try {
        storageService.deleteIfExists(storageKey);
      } catch (Exception e) {
        PaperFlowLog.error("PAPER_INPUT_ORIGINAL_CLEANUP_FAILED key={}", e, storageKey);
      }
    }
  }

  private void abandonUnusableInputRegistration(Long paperId) {
    try {
      transactionalExecutor.run(() -> {
        paperInputFileRepository.deleteByPaperId(paperId);
        resumeRepository.deleteById(paperId);
      });
    } catch (Exception e) {
      resumeRepository.failInputIfPending(
          paperId, "原始文件保存失败，请重新上传", LocalDateTime.now());
      PaperFlowLog.error("PAPER_INPUT_REGISTRATION_CLEANUP_FAILED paperId={}", e, paperId);
    }
  }

  private record PreparedUpload(String filename, byte[] bytes, String contentType, long size) {
  }

  /**
   * 纯文字材料直接进入切题：从已落库的页清洗合并正文、置 NONE（未走页级识别），投递 paper:extract。
   */
  PaperUploadResult enqueueDirectExtraction(PracticePaperEntity paper,
                                            int totalPages) {
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paper.getId());
    List<PaperPageEntity> readyPages = pages.stream()
        .filter(page -> page.getStatus() == PageStatus.READY)
        .toList();
    MergeResult merge = pageTextMerger.mergePlain(readyPages);
    PaperFlowLog.event("DIRECT_EXTRACTION_MERGE paperId={}, readyPages={}, pages={}, textChars={}, "
            + "questionNos={}, warnings={}",
        paper.getId(), readyPages.size(), totalPages,
        merge.text() == null ? 0 : merge.text().length(), merge.questionNos(), merge.warnings());
    PaperFlowLog.raw("DIRECT_EXTRACTION_TEXT paperId=" + paper.getId(), merge.text());
    if (merge.text() == null || merge.text().isBlank()) {
      throw new BusinessException(ErrorCode.PAPER_PARSE_FAILED, "无法从文件中提取文本内容");
    }

    transactionalExecutor.run(() -> {
      PracticePaperEntity saved = resumeRepository.findById(paper.getId()).orElseThrow();
      saved.setResumeText(merge.text());
      saved.setRecognitionStatus(RecognitionStatus.NONE);
      saved.setRecognitionError(null);
      saved.setRecognitionAttemptId(null);
      saved.setRecognitionUpdatedAt(LocalDateTime.now());
      saved.setAnalysisStatus(AsyncTaskStatus.PENDING);
      saved.setAnalysisError(null);
      saved.setAnalysisAttemptId(null);
      saved.setAnalysisUpdatedAt(LocalDateTime.now());
      resumeRepository.save(saved);
    });

    if (!extractionProducer.sendExtractTask(paper.getId())) {
      PaperFlowLog.error("DIRECT_EXTRACTION_ENQUEUE_FAILED paperId={}",
          new BusinessException(ErrorCode.INTERNAL_ERROR, "切题任务投递失败"),
          paper.getId());
      transactionalExecutor.run(() -> resumeRepository.failAnalyzeIfPending(
          paper.getId(), "切题任务投递失败，请稍后重试", LocalDateTime.now()));
      return new PaperUploadResult(paper.getId(), totalPages, RecognitionStatus.FAILED,
          ProcessingPath.DIRECT_EXTRACTION, false,
          "试卷已保存，但切题任务投递失败，请稍后重试");
    }
    PaperFlowLog.event("DIRECT_EXTRACTION_ENQUEUED paperId={}, pages={}",
        paper.getId(), totalPages);
    return new PaperUploadResult(paper.getId(), totalPages, RecognitionStatus.NONE,
        ProcessingPath.DIRECT_EXTRACTION, false,
        "文本已直接进入切题");
  }

  void createPages(PracticePaperEntity paper, List<ParsedDocument> documents,
                   List<String> uploadedKeys) {
    List<PaperPageEntity> pages = new ArrayList<>();
    int pageOffset = 0;
    for (ParsedDocument document : documents) {
      for (ParsedPage parsed : document.pages()) {
        int pageNo = ++pageOffset;
        String imageKey = null;
        if (parsed.pageImage() != null && parsed.pageImage().length > 0) {
          long uploadStart = System.nanoTime();
          imageKey = storageService.uploadPageImage(paper.getId(), pageNo, parsed.pageImage());
          uploadedKeys.add(imageKey);
          PaperFlowLog.event("PAGE_IMAGE_STORED paperId={}, pageNo={}, bytes={}, key={}, "
                  + "durationMs={}",
              paper.getId(), pageNo, parsed.pageImage().length, imageKey,
              elapsedMs(uploadStart));
        }
        PaperPageEntity page = new PaperPageEntity();
        page.setPaperId(paper.getId());
        page.setPageNo(pageNo);
        page.setSource(parsed.source());
        page.setImageKey(imageKey);
        page.setLogicalPage(parsed.logicalPage());
        page.setUpdatedAt(LocalDateTime.now());
        Map<String, String> assetKeyMapping = Map.of();
        if (!parsed.assets().isEmpty()) {
          PaperAssetService.PageAssetWriteResult writeResult =
              assetService.replacePageGeneration(
                  paper.getId(), pageNo, parsed.assets(), null, PaperAssetKey.INITIAL_ATTEMPT);
          assetKeyMapping = writeResult.originalKeyToVersionedKey();
        }
        if (parsed.source() == PageSource.TEXT) {
          page.setTextContent(String.join("\n", parsed.lines()));
          page.setAssetsJson(writeGeometry(TextPageGeometry.fromParsedPage(
              pageNo, parsed.lines(), parsed.assets(), assetKeyMapping)));
          page.setStatus(PageStatus.READY);
        } else {
          page.setStatus(PageStatus.PENDING);
        }
        pages.add(page);
        PaperFlowLog.event("PARSED_PAGE_CREATED paperId={}, pageNo={}, source={}, logicalPage={}, "
                + "status={}, textChars={}, assetCount={}, imageKey={}",
            paper.getId(), pageNo, page.getSource(), page.isLogicalPage(), page.getStatus(),
            page.getTextContent() == null ? 0 : page.getTextContent().length(),
            parsed.assets().size(), imageKey);
      }
    }
    transactionalExecutor.run(() -> pageRepository.saveAll(pages));
    PaperFlowLog.event("PAGE_ENTITIES_SAVED paperId={}, pages={}", paper.getId(), pages.size());
  }

  private String writeGeometry(PageRecognitionGeometrySnapshot geometry) {
    try {
      return objectMapper.writeValueAsString(geometry);
    } catch (JacksonException e) {
      PaperFlowLog.error("TEXT_PAGE_GEOMETRY_SERIALIZE_FAILED pageNo={}",
          e, geometry == null ? null : geometry.pageNo());
      throw new BusinessException(ErrorCode.BAD_REQUEST, "页几何快照序列化失败", e);
    }
  }

  DocumentParser findParser(String contentType, String filename) {
    return documentParsers.stream()
        .filter(parser -> parser.supports(contentType, filename))
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
            "不支持的文件类型：" + (contentType == null ? filename : contentType)));
  }

  String resolveSourceType(List<ParsedDocument> documents) {
    for (ParsedDocument document : documents) {
      if ("DOCX".equals(document.sourceType()) || "DOC".equals(document.sourceType())) {
        return document.sourceType();
      }
    }
    return documents.isEmpty() ? "PDF" : documents.get(0).sourceType();
  }

  /**
   * 已有试卷的处理路径无法从库里直读，按「是否走过页级识别」反推：NONE 表示纯文字直切。
   */
  private ProcessingPath resolveProcessingPath(PracticePaperEntity paper) {
    return paper.getRecognitionStatus() == RecognitionStatus.NONE
        ? ProcessingPath.DIRECT_EXTRACTION
        : ProcessingPath.VISION_RECOGNITION;
  }

  private PaperPageEntity buildPage(Long paperId, int pageNo, String text, String imageKey) {
    PageSource source = decideSource(text);
    PaperPageEntity page = new PaperPageEntity();
    page.setPaperId(paperId);
    page.setPageNo(pageNo);
    page.setSource(source);
    page.setImageKey(imageKey);
    page.setUpdatedAt(LocalDateTime.now());
    if (source == PageSource.TEXT) {
      page.setTextContent(text.strip());
      page.setStatus(PageStatus.READY);
    } else {
      page.setStatus(PageStatus.PENDING);
    }
    return page;
  }

  /**
   * 这一页的文字层能不能直接用。
   * <p>
   * 抽不到 / 像坏码 → 图片；有效字符 ≥30 或含行首题号 → 文本；否则按图片兜底。
   * </p>
   */
  PageSource decideSource(String text) {
    if (text == null || text.isBlank()) {
      return PageSource.IMAGE;
    }
    int total = 0;
    int effective = 0;
    int bad = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (Character.isWhitespace(ch)) {
        continue;
      }
      total++;
      if (ch == '\uFFFD' || Character.isISOControl(ch)) {
        bad++;
      }
      if (Character.isLetterOrDigit(ch)) {
        effective++;
      }
    }
    if (total == 0) {
      return PageSource.IMAGE;
    }
    if ((double) bad / total > 0.2) {
      return PageSource.IMAGE;
    }
    if (effective >= 30 || PageTextMerger.hasLeadingQuestionNo(text)) {
      return PageSource.TEXT;
    }
    return PageSource.IMAGE;
  }

  // ==================== 识别执行（Stream 消费者调用） ====================

  /**
   * 逐页处理 PENDING 页。单页失败只标记该页，其它页继续。
   *
   * @param attemptId 试卷级执行代次（心跳用）
   */
  public void processPaper(Long paperId, String attemptId) {
    PracticePaperEntity paper = resumeRepository.findById(paperId).orElse(null);
    if (paper == null) {
      log.warn("试卷已删除，跳过识别任务: paperId={}", paperId);
      PaperFlowLog.warn("PAPER_RECOGNITION_SKIPPED reason=PAPER_DELETED attemptId={}", attemptId);
      return;
    }
    long startNanos = System.nanoTime();
    try (PaperFlowLog.Scope ignored =
             PaperFlowLog.open("PAPER_RECOGNITION", paperId, attemptId, null)) {
      PaperFlowLog.event("PAPER_RECOGNITION_BEGIN recognitionStatus={}, analysisStatus={}, "
              + "pageCount={}, recoveryCount={}",
          paper.getRecognitionStatus(), paper.getAnalysisStatus(), paper.getPageCount(),
          paper.getRecognitionRecoveryCount());
      pageRecognitionProcessor.resetStalePages(paperId);
      List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
      if (pages.isEmpty()) {
        throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED, "试卷没有可识别的页");
      }
      pageRecognitionProcessor.process(
          paperId,
          pages,
          (page, pageAttemptId) ->
              recognizePage(page, pageAttemptId, paper.getPageCount()),
          () -> heartbeat(paperId, attemptId));
      pageRecognitionProcessor.failIfAllPagesFailed(paperId);
      PaperFlowLog.event("PAPER_RECOGNITION_END status=SUCCESS, durationMs={}, pageStates={}",
          elapsedMs(startNanos), pageStates(pageRepository.findByPaperIdOrderByPageNoAsc(paperId)));
    } catch (RuntimeException e) {
      PaperFlowLog.error("PAPER_RECOGNITION_END status=FAILED, durationMs={}",
          e, elapsedMs(startNanos));
      throw e;
    }
  }

  private RecognizedPage recognizePage(
      PaperPageEntity page,
      String pageAttemptId,
      Integer pageCount
  ) {
    long start = System.currentTimeMillis();
    String providerId = llmProviderRegistry.getCurrentUserVisionProviderId();
    String model = llmProviderRegistry.getCurrentUserVisionModelName();
    log.info("开始识别试卷页: paperId={}, pageNo={}, attemptId={}, provider={}, model={}",
        page.getPaperId(), page.getPageNo(), pageAttemptId, providerId, model);
    PaperFlowLog.event("PAGE_RECOGNIZE_BEGIN imageKey={}, provider={}, model={}",
        page.getImageKey(), providerId, model);
    byte[] jpeg = storageService.downloadFile(page.getImageKey());
    PaperFlowLog.event("PAGE_IMAGE_DOWNLOADED imageKey={}, bytes={}",
        page.getImageKey(), jpeg == null ? 0 : jpeg.length);
    PageRecognition result = pageVisionRecognizer.recognize(
        page,
        pageCount,
        jpeg,
        llmProviderRegistry.getDefaultVisionChatClient()
    );
    PaperFlowLog.json(objectMapper, "PAGE_VISION_RESULT_RAW", result);
    return finishRecognition(page, jpeg, result, pageAttemptId, start, model);
  }

  private RecognizedPage finishRecognition(
      PaperPageEntity page,
      byte[] jpeg,
      PageRecognition result,
      String pageAttemptId,
      long start,
      String model
  ) {
    if (result == null) {
      PaperFlowLog.error("PAGE_FINISH_FAILED reason=NO_RESULT pageNo={}",
          new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED, "没有可用的识别结果"),
          page.getPageNo());
      throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
          "第 " + page.getPageNo() + " 页没有可用的识别结果");
    }
    String text = pageText(result);
    PaperFlowLog.raw("PAGE_OCR_TEXT pageNo=" + page.getPageNo(), text);
    boolean hasAssets = result.assets() != null && !result.assets().isEmpty();
    boolean hasVisualContent = result.hasFigure() || hasAssets;
    boolean usableLayout = hasUsableLayout(result.lines());
    PaperFlowLog.event("PAGE_FINISH_VALIDATION pageNo={}, chars={}, lines={}, assets={}, "
            + "hasFigure={}, hasVisualContent={}, usableLayout={}, notes={}",
        page.getPageNo(), text.length(), result.lines() == null ? 0 : result.lines().size(),
        result.assets() == null ? 0 : result.assets().size(), result.hasFigure(),
        hasVisualContent, usableLayout, result.notes());
    if (hasVisualContent && !usableLayout) {
      throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
          "第 " + page.getPageNo() + " 页缺少行级布局坐标，无法可靠定位图片，请重试该页");
    }
    if (recognitionValidator.hasMissingVisualAssets(text, result.hasFigure(), hasAssets)) {
      throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
          "第 " + page.getPageNo() + " 页检测到图形内容但未识别到图片资源，请重试该页");
    }
    if (recognitionValidator.isLikelyIncomplete(jpeg, text, hasVisualContent)) {
      throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
          "第 " + page.getPageNo() + " 页多次识别内容异常偏少，请重试该页");
    }
    boolean hasOptionRow = hasAssetKind(result.assets(), "OPTION_ROW");
    if (recognitionValidator.hasMissingDeclaredStemFigureAssets(
        result.stemFigureAnchors(), result.assets()) && !hasOptionRow) {
      throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
          "第 " + page.getPageNo() + " 页声明了题干图但缺少对应图片资源，请重试该页");
    }
    String notes = result.notes();
    if (text.isEmpty()) {
      notes = appendNote(notes, "本页未识别到文字");
    } else if (text.length() < 20 && !hasVisualContent) {
      notes = appendNote(notes, "识别内容较少，请人工核对");
    }
    if (recognitionValidator.hasMissingRequiredMarkup(text)) {
      notes = appendNote(notes, "检测到“加点”文字但未识别出标记，请校对");
    }
    if (!pageRepository.existsByPaperIdAndPageNoAndStatusAndRecognitionAttemptId(
        page.getPaperId(), page.getPageNo(), PageStatus.PROCESSING, pageAttemptId)) {
      PaperFlowLog.error("PAGE_FENCING_CHECK_FAILED pageNo={}, attemptId={}, expectedStatus={}",
          new BusinessException(ErrorCode.INTERNAL_ERROR, "页识别执行权已失效"),
          page.getPageNo(), pageAttemptId, PageStatus.PROCESSING);
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "第 " + page.getPageNo() + " 页识别执行权已失效");
    }
    PaperFlowLog.event("PAGE_FENCING_CHECK_OK pageNo={}, attemptId={}",
        page.getPageNo(), pageAttemptId);
    PageRecognitionGeometrySnapshot geometry = saveRecognizedAssets(
        page, jpeg, result.assets(), result.lines(), result.stemFigureAnchors(),
        pageAttemptId);
    PaperFlowLog.json(objectMapper, "PAGE_GEOMETRY_RESULT", geometry);
    log.info("试卷页识别完成: paperId={}, pageNo={}, chars={}, durationMs={}",
        page.getPaperId(), page.getPageNo(), text.length(), System.currentTimeMillis() - start);
    PaperFlowLog.event("PAGE_RECOGNIZE_END status=SUCCESS, pageNo={}, chars={}, assets={}, "
            + "geometryAssets={}, durationMs={}",
        page.getPageNo(), text.length(), result.assets() == null ? 0 : result.assets().size(),
        geometry.assets().size(), System.currentTimeMillis() - start);
    return new RecognizedPage(text, hasVisualContent, notes, model, geometry);
  }

  /**
   * 视觉模型只给归一化 bbox；这里按 bbox 从页图裁出资源，落 S3 + paper_assets。
   */
  private PageRecognitionGeometrySnapshot saveRecognizedAssets(
      PaperPageEntity page,
      byte[] pageJpeg,
      List<PageRecognition.PageAsset> assets,
      List<PageRecognition.PageLine> lines,
      List<String> stemFigureAnchors,
      String pageAttemptId
  ) {
    if (assets == null || assets.isEmpty()) {
      PaperFlowLog.event("PAGE_ASSETS_SAVE_SKIPPED pageNo={}, lines={}, reason=NO_ASSETS",
          page.getPageNo(), lines == null ? 0 : lines.size());
      return new PageRecognitionGeometrySnapshot(
          PageRecognitionGeometrySnapshot.CURRENT_VERSION,
          page.getPageNo(),
          lines == null ? List.of() : lines,
          List.of());
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(pageJpeg));
      if (image == null) {
        throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
            "第 " + page.getPageNo() + " 页原图无法读取，无法保存图片资源");
      }
      PaperFlowLog.event("PAGE_ASSET_RESOLVE_BEGIN pageNo={}, image={}x{}, inputAssets={}, "
              + "lines={}, stemAnchors={}",
          page.getPageNo(), image.getWidth(), image.getHeight(), assets.size(),
          lines == null ? 0 : lines.size(), stemFigureAnchors);
      PaperFlowLog.json(objectMapper, "PAGE_INPUT_ASSETS pageNo=" + page.getPageNo(), assets);
      List<DocAsset> docAssets = new ArrayList<>();
      Set<String> usedKeys = new HashSet<>();
      List<ResolvedAsset> resolvedAssets =
          resolveRecognizedAssets(image, assets, lines, page.getPageNo(), stemFigureAnchors);
      PaperFlowLog.json(objectMapper, "PAGE_RESOLVED_ASSETS pageNo=" + page.getPageNo(),
          resolvedAssets);
      for (ResolvedAsset resolved :
          resolvedAssets) {
        PageRecognition.PageAsset asset = resolved.asset();
        AssetKind kind = assetKind(asset);
        BufferedImage crop = image.getSubimage(
            resolved.rect().x, resolved.rect().y,
            resolved.rect().width, resolved.rect().height);
        if (kind == AssetKind.FIGURE || kind == AssetKind.TABLE) {
          crop = imageNormalizer.upscaleVisionAsset(crop);
        }
        byte[] bytes = imageNormalizer.toJpeg(crop);
        String key = uniqueKey(resolved.baseKey(), usedKeys);
        AssetRole role;
        try {
          role = AssetRole.valueOf(asset.role() == null ? "STEM" : asset.role().toUpperCase());
        } catch (Exception e) {
          role = AssetRole.STEM;
        }
        docAssets.add(new DocAsset(key, kind, role, bytes, resolved.anchorText(),
            asset.layout(), asset.optionCount(), asset.labels(),
            PageGeometry.normalizedRect(resolved.rect(), image.getWidth(), image.getHeight()),
            crop.getWidth(), crop.getHeight()));
      }
      PaperFlowLog.value("PAGE_DOC_ASSETS pageNo=" + page.getPageNo(),
          docAssetSummaries(docAssets));
      failOnStemFigureMismatch(
          page, pageAttemptId, stemFigureAnchors,
          resolvedAssets.stream().map(ResolvedAsset::asset).toList());
      if (docAssets.isEmpty()) {
        throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
            "第 " + page.getPageNo() + " 页识别到了图形引用，但没有可保存的图片区域");
      }
      PaperAssetService.PageAssetWriteResult writeResult =
          assetService.replacePageGeneration(
              page.getPaperId(), page.getPageNo(), docAssets, null, pageAttemptId);
      List<PaperAssetEntity> saved = writeResult.entities();
      PaperFlowLog.value("PAGE_SAVED_ASSET_ENTITIES pageNo=" + page.getPageNo(),
          saved == null ? null : saved.stream().map(this::assetSummary).toList());
      if (saved == null || saved.isEmpty()) {
        throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
            "第 " + page.getPageNo() + " 页图片资源保存失败");
      }
      Map<String, PaperAssetEntity> savedByKey = new LinkedHashMap<>();
      for (PaperAssetEntity entity : saved) {
        savedByKey.put(entity.getAssetKey(), entity);
      }
      List<PageAssetGeometry> geometries = new ArrayList<>();
      for (DocAsset docAsset : docAssets) {
        String versionedKey = writeResult.originalKeyToVersionedKey().get(docAsset.key());
        PaperAssetEntity entity = savedByKey.get(versionedKey);
        if (entity == null) {
          continue;
        }
        geometries.add(new PageAssetGeometry(
            entity.getAssetKey(),
            entity.getKind(),
            entity.getRole(),
            entity.getAnchorText(),
            docAsset.rect() == null ? parseRect(entity.getRectJson()) : docAsset.rect(),
            entity.getLayout(),
            entity.getOptionCount()
        ));
      }
      return new PageRecognitionGeometrySnapshot(
          PageRecognitionGeometrySnapshot.CURRENT_VERSION,
          page.getPageNo(),
          lines == null ? List.of() : lines,
          geometries
      );
    } catch (BusinessException e) {
      PaperFlowLog.error("PAGE_ASSETS_SAVE_FAILED pageNo={}, type=BUSINESS",
          e, page.getPageNo());
      throw e;
    } catch (Exception e) {
      log.error("保存页资源失败: paperId={}, pageNo={}, error={}",
          page.getPaperId(), page.getPageNo(), e.getMessage(), e);
      PaperFlowLog.error("PAGE_ASSETS_SAVE_FAILED pageNo={}, type=UNEXPECTED",
          e, page.getPageNo());
      throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
          "第 " + page.getPageNo() + " 页图片资源保存失败，请重试该页", e);
    }
  }

  private double[] parseRect(String rectJson) {
    if (rectJson == null || rectJson.isBlank()) {
      return null;
    }
    try {
      String normalized = rectJson.replace("[", "").replace("]", "");
      String[] parts = normalized.split(",");
      if (parts.length < 4) {
        return null;
      }
      double[] rect = new double[4];
      for (int i = 0; i < 4; i++) {
        rect[i] = Double.parseDouble(parts[i].trim());
      }
      return rect;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private List<ResolvedAsset> resolveRecognizedAssets(
      BufferedImage image,
      List<PageRecognition.PageAsset> assets,
      List<PageRecognition.PageLine> lines,
      int pageNo,
      List<String> stemFigureAnchors
  ) {
    List<ResolvedAsset> resolved = new ArrayList<>();
    List<TableSeed> tableSeeds = new ArrayList<>();
    int index = 0;
    for (PageRecognition.PageAsset asset : assets) {
      index++;
      PageGeometry.PixelRectResolution resolution =
          PageGeometry.resolvePixelRect(asset, image.getWidth(), image.getHeight());
      Rectangle rect = resolution.rect();
      PaperFlowLog.event("MODEL_ASSET_BBOX index={}, kind={}, role={}, bbox={}, layout={}, "
              + "optionCount={}, labels={}, anchor={}, resolved={}, rect={}, failureReason={}",
          index, asset.kind(), asset.role(), arrayText(asset.bbox()), asset.layout(),
          asset.optionCount(), asset.labels(), asset.anchorText(), resolution.resolved(),
          rect, resolution.failureReason());
      if (rect == null) {
        PaperFlowLog.warn("MODEL_ASSET_DROPPED index={}, kind={}, reason={}",
            index, asset.kind(), resolution.failureReason());
        continue;
      }
      String key = "p" + pageNo + "a" + index;
      if (assetKind(asset) == AssetKind.TABLE) {
        tableSeeds.add(new TableSeed(asset, key, rect));
      } else {
        PaperLayoutAssetResolver.ResolvedRegion layout =
            layoutAssetResolver.resolve(image, rect, asset, lines);
        rect = layout.region();
        if (assetKind(asset) == AssetKind.FIGURE) {
          rect = figureCropResolver.expand(image, rect, layout.questionBand());
        }
        if (assetKind(asset) == AssetKind.FIGURE
            || assetKind(asset) == AssetKind.OPTION_ROW) {
          rect = visualBlockResolver.resolve(image, rect, layout.questionBand());
        }
        PaperFlowLog.event("MODEL_ASSET_LAYOUT_RESOLVED index={}, key={}, kind={}, role={}, "
                + "initialRect={}, finalRect={}, questionBand={}, anchor={}",
            index, key, asset.kind(), asset.role(),
            PageGeometry.resolvePixelRect(asset, image.getWidth(), image.getHeight()).rect(),
            rect, layout.questionBand(), asset.anchorText());
        resolved.add(new ResolvedAsset(
            asset, key, rect, asset.anchorText(), layout.questionBand()));
      }
    }

    for (List<TableSeed> group : groupOverlappingTables(tableSeeds)) {
      Rectangle searchArea = PageGeometry.union(group.stream().map(TableSeed::rect).toList());
      List<Rectangle> regions = tableRegionDetector.detect(image, searchArea);
      PaperFlowLog.event("TABLE_REGIONS_DETECTED seeds={}, searchArea={}, regions={}",
          group.stream().map(TableSeed::baseKey).toList(), searchArea, regions);
      if (regions.isEmpty()) {
        regions = group.stream().map(TableSeed::rect).distinct().toList();
      }
      Map<String, TableSeed> seedByKey = new LinkedHashMap<>();
      Map<String, List<Rectangle>> regionsByKey = new LinkedHashMap<>();
      for (Rectangle region : regions) {
        TableSeed nearest = nearestSeed(group, region);
        seedByKey.put(nearest.baseKey(), nearest);
        regionsByKey.computeIfAbsent(nearest.baseKey(), key -> new ArrayList<>()).add(region);
      }
      for (Map.Entry<String, List<Rectangle>> entry : regionsByKey.entrySet()) {
        TableSeed seed = seedByKey.get(entry.getKey());
        Rectangle merged = PageGeometry.union(entry.getValue());
        PaperLayoutAssetResolver.ResolvedRegion layout =
            layoutAssetResolver.resolve(image, merged, seed.asset(), lines);
        merged = layout.region();
        PaperFlowLog.event("TABLE_REGION_RESOLVED key={}, seedRect={}, mergedRect={}, "
                + "questionBand={}",
            seed.baseKey(), seed.rect(), merged, layout.questionBand());
        resolved.add(new ResolvedAsset(
            seed.asset(), seed.baseKey(), merged, seed.asset().anchorText(),
            layout.questionBand()));
      }
    }
    List<ResolvedAsset> normalized = resolved.stream()
        .sorted(Comparator.comparingInt((ResolvedAsset item) -> item.rect().y)
            .thenComparingInt(item -> item.rect().x))
        .toList();
    PaperFlowLog.value("MODEL_ASSETS_RESOLVED_NORMALIZED count=" + normalized.size(),
        normalizedSummaries(normalized));
    List<ResolvedAsset> completed =
        addMissingStemAssets(image, lines, normalized, stemFigureAnchors);
    PaperFlowLog.value("MODEL_ASSETS_AFTER_MISSING_STEM count=" + completed.size(),
        normalizedSummaries(completed));
    return completed;
  }

  private List<ResolvedAsset> addMissingStemAssets(
      BufferedImage image,
      List<PageRecognition.PageLine> lines,
      List<ResolvedAsset> assets,
      List<String> stemFigureAnchors
  ) {
    List<ResolvedAsset> completed = new ArrayList<>(assets);
    for (ResolvedAsset optionRow : assets) {
      if (assetKind(optionRow.asset()) != AssetKind.OPTION_ROW) {
        continue;
      }
      boolean hasStemFigure = assets.stream().anyMatch(candidate ->
          assetKind(candidate.asset()) == AssetKind.FIGURE
              && !"OPTIONS".equalsIgnoreCase(candidate.asset().role())
              && candidate.rect().intersects(optionRow.questionBand())
              && PageGeometry.inSameColumn(candidate.rect(), optionRow.rect()));
      if (hasStemFigure) {
        continue;
      }
      String questionText = questionTextInBand(
          image, lines, optionRow.rect(), optionRow.questionBand());
      if (questionText.isBlank()) {
        questionText = optionRow.anchorText();
      }
      String declaredAnchor = matchingStemAnchor(
          stemFigureAnchors, questionText, optionRow.anchorText());
      String anchorText = declaredAnchor == null ? optionRow.anchorText() : declaredAnchor;
      Optional<PageRecognition.PageAsset> missingStem = missingStemAssetResolver.resolve(
          image,
          optionRow.rect(),
          optionRow.questionBand(),
          questionText,
          anchorText,
          declaredAnchor != null
      );
      PaperFlowLog.event("MISSING_STEM_ASSET_RESOLVE optionKey={}, declaredAnchor={}, "
              + "questionText={}, optionAnchor={}, found={}, resolvedAsset={}",
          optionRow.baseKey(), declaredAnchor, questionText, optionRow.anchorText(),
          missingStem.isPresent(), missingStem.orElse(null));
      missingStem.ifPresent(asset -> {
        PageGeometry.PixelRectResolution resolution =
            PageGeometry.resolvePixelRect(asset, image.getWidth(), image.getHeight());
        PaperFlowLog.event("MISSING_STEM_ASSET_BBOX key={}, bbox={}, rect={}, failureReason={}",
            optionRow.baseKey() + "-stem", arrayText(asset.bbox()), resolution.rect(),
            resolution.failureReason());
        completed.add(new ResolvedAsset(
            asset,
            optionRow.baseKey() + "-stem",
            resolution.rect(),
            asset.anchorText(),
            optionRow.questionBand()
        ));
      });
    }
    return completed.stream()
        .sorted(Comparator.comparingInt((ResolvedAsset item) -> item.rect().y)
            .thenComparingInt(item -> item.rect().x))
        .toList();
  }

  private String matchingStemAnchor(
      List<String> stemFigureAnchors,
      String questionText,
      String optionAnchor
  ) {
    if (stemFigureAnchors == null || stemFigureAnchors.isEmpty()) {
      return null;
    }
    String question = normalizeAnchor(questionText);
    String option = normalizeAnchor(optionAnchor);
    return stemFigureAnchors.stream()
        .filter(anchor -> anchor != null && !anchor.isBlank())
        .filter(anchor -> {
          String normalized = normalizeAnchor(anchor);
          return !normalized.isBlank()
              && (question.contains(normalized)
                  || normalized.contains(question) && question.length() >= 4
                  || option.contains(normalized)
                  || normalized.contains(option) && option.length() >= 4);
        })
        .findFirst()
        .orElse(null);
  }

  private String normalizeAnchor(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "");
  }

  private String questionTextInBand(
      BufferedImage image,
      List<PageRecognition.PageLine> lines,
      Rectangle column,
      Rectangle band
  ) {
    if (lines == null) {
      return "";
    }
    int left = column.x - 20;
    int right = column.x + column.width + 20;
    return lines.stream()
        .filter(line -> line != null && line.text() != null && line.bbox() != null
            && line.bbox().length >= 4)
        .filter(line -> {
          Rectangle lineRect = PageGeometry.pixelRect(line, image);
          int centerX = lineRect.x + lineRect.width / 2;
          return centerX >= left && centerX <= right;
        })
        .filter(line -> {
          Rectangle lineRect = PageGeometry.pixelRect(line, image);
          int centerY = lineRect.y + lineRect.height / 2;
          return centerY >= band.y && centerY <= band.y + band.height;
        })
        .map(PageRecognition.PageLine::text)
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private String pageText(PageRecognition result) {
    if (result.lines() != null && !result.lines().isEmpty()) {
      String joined = result.lines().stream()
          .map(PageRecognition.PageLine::text)
          .filter(text -> text != null && !text.isBlank())
          .map(String::strip)
          .collect(java.util.stream.Collectors.joining("\n"));
      if (!joined.isBlank()) {
        return joined;
      }
    }
    return result.text() == null ? "" : result.text().strip();
  }

  private boolean hasUsableLayout(List<PageRecognition.PageLine> lines) {
    return lines != null && lines.stream().anyMatch(line ->
        line != null && line.bbox() != null && line.bbox().length >= 4
            && line.text() != null && !line.text().isBlank());
  }

  private List<List<TableSeed>> groupOverlappingTables(List<TableSeed> seeds) {
    List<List<TableSeed>> groups = new ArrayList<>();
    for (TableSeed seed : seeds) {
      List<List<TableSeed>> overlapping = groups.stream()
          .filter(group -> group.stream()
              .anyMatch(existing -> existing.rect().intersects(seed.rect())))
          .toList();
      if (overlapping.isEmpty()) {
        List<TableSeed> group = new ArrayList<>();
        group.add(seed);
        groups.add(group);
        continue;
      }
      List<TableSeed> target = overlapping.get(0);
      target.add(seed);
      for (int i = 1; i < overlapping.size(); i++) {
        target.addAll(overlapping.get(i));
        groups.remove(overlapping.get(i));
      }
    }
    return groups;
  }

  private TableSeed nearestSeed(List<TableSeed> seeds, Rectangle region) {
    return seeds.stream()
        .min(Comparator.comparingDouble(seed -> PageGeometry.anchorDistance(seed.rect(), region)))
        .orElseThrow();
  }

  private AssetKind assetKind(PageRecognition.PageAsset asset) {
    try {
      return AssetKind.valueOf(asset.kind() == null ? "FIGURE" : asset.kind().toUpperCase());
    } catch (IllegalArgumentException e) {
      PaperFlowLog.warn("MODEL_ASSET_KIND_INVALID kind={}, fallback=FIGURE", asset.kind());
      return AssetKind.FIGURE;
    }
  }

  private boolean hasAssetKind(List<PageRecognition.PageAsset> assets, String kind) {
    return assets != null && assets.stream()
        .anyMatch(asset -> asset != null && kind.equalsIgnoreCase(asset.kind()));
  }

  private String uniqueKey(String baseKey, Set<String> usedKeys) {
    if (usedKeys.add(baseKey)) {
      return baseKey;
    }
    int suffix = 2;
    while (!usedKeys.add(baseKey + "-" + suffix)) {
      suffix++;
    }
    return baseKey + "-" + suffix;
  }

  private List<String> fileSummaries(List<MultipartFile> files) {
    if (files == null) {
      return List.of();
    }
    return files.stream()
        .map(file -> file == null ? "null"
            : "name=" + file.getOriginalFilename()
                + ",size=" + file.getSize()
                + ",contentType=" + file.getContentType())
        .toList();
  }

  private List<String> pageStates(List<PaperPageEntity> pages) {
    if (pages == null) {
      return List.of();
    }
    return pages.stream()
        .map(page -> "page=" + page.getPageNo()
            + ",source=" + page.getSource()
            + ",status=" + page.getStatus()
            + ",attempt=" + page.getAttempt()
            + ",attemptId=" + page.getRecognitionAttemptId()
            + ",error=" + page.getError())
        .toList();
  }

  private List<String> docAssetSummaries(List<DocAsset> assets) {
    return assets.stream()
        .map(asset -> "key=" + asset.key()
            + ",kind=" + asset.kind()
            + ",role=" + asset.role()
            + ",anchor=" + asset.anchorText()
            + ",rect=" + arrayText(asset.rect())
            + ",width=" + asset.width()
            + ",height=" + asset.height()
            + ",bytes=" + (asset.bytes() == null ? 0 : asset.bytes().length)
            + ",layout=" + asset.layout()
            + ",optionCount=" + asset.optionCount()
            + ",labels=" + asset.labels())
        .toList();
  }

  private List<String> normalizedSummaries(List<ResolvedAsset> assets) {
    return assets.stream()
        .map(item -> "asset=" + item.asset().kind() + "/" + item.asset().role()
            + ",key=" + item.baseKey()
            + ",anchor=" + item.anchorText()
            + ",rect=" + item.rect()
            + ",bbox=" + arrayText(item.asset().bbox())
            + ",questionBand=" + item.questionBand())
        .toList();
  }

  private String assetSummary(PaperAssetEntity asset) {
    return "key=" + asset.getAssetKey()
        + ",kind=" + asset.getKind()
        + ",role=" + asset.getRole()
        + ",anchor=" + asset.getAnchorText()
        + ",rect=" + asset.getRectJson()
        + ",width=" + asset.getWidth()
        + ",height=" + asset.getHeight()
        + ",storageKey=" + asset.getStorageKey();
  }

  private String arrayText(double[] values) {
    if (values == null) {
      return "null";
    }
    StringBuilder result = new StringBuilder("[");
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        result.append(',');
      }
      result.append(values[i]);
    }
    return result.append(']').toString();
  }

  private long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private record TableSeed(PageRecognition.PageAsset asset, String baseKey, Rectangle rect) {
  }

  private record ResolvedAsset(
      PageRecognition.PageAsset asset,
      String baseKey,
      Rectangle rect,
      String anchorText,
      Rectangle questionBand
  ) {
  }

  static String pageFailureMessage(Throwable error) {
    if (error instanceof BusinessException businessError
        && businessError.getMessage() != null
        && !businessError.getMessage().isBlank()) {
      return businessError.getMessage();
    }
    return error == null ? "unknown" : error.getClass().getSimpleName();
  }

  // ==================== 查询 / 人工校对 ====================

  public PaperRecognitionDTO getRecognition(Long paperId) {
    PracticePaperEntity paper = requirePaper(paperId);
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
    List<PaperPageEntity> readyPages = pages.stream()
        .filter(page -> page.getStatus() == PageStatus.READY)
        .toList();
    MergeResult merge = readyPages.isEmpty() ? null : pageTextMerger.merge(readyPages);

    List<PaperAssetEntity> allAssets = assetService.list(paperId);
    Map<Integer, Integer> assetCountByPage = allAssets.stream()
        .filter(asset -> asset.getPageNo() != null)
        .collect(Collectors.groupingBy(PaperAssetEntity::getPageNo,
            Collectors.summingInt(asset -> 1)));
    List<Integer> geometryMissingPages = pages.stream()
        .filter(page -> geometryIssueRequiresRecognition(
            page, assetCountByPage.getOrDefault(page.getPageNo(), 0)))
        .map(PaperPageEntity::getPageNo)
        .toList();

    List<PaperRecognitionDTO.PaperPageDTO> pageDtos = pages.stream()
        .map(page -> {
          int assetCount = assetCountByPage.getOrDefault(page.getPageNo(), 0);
          PageGeometryInspector.Inspection inspection = PageGeometryInspector.inspect(
              page.getSource(), page.getAssetsJson(), assetCount, objectMapper);
          return new PaperRecognitionDTO.PaperPageDTO(
              page.getPageNo(),
              page.getSource(),
              page.getStatus(),
              page.getTextContent(),
              page.isHasFigure(),
              page.getNotes(),
              page.getError(),
              "/api/papers/" + paperId + "/pages/" + page.getPageNo() + "/image",
              inspection.issue().name(),
              assetCount
          );
        })
        .toList();

    AssetScope assetScope = resolveAssetScope(pages);
    List<PaperRecognitionDTO.PaperAssetDTO> assetDtos = allAssets.stream()
        .filter(asset -> asset.getAssetKey() != null)
        .filter(asset -> assetScope.assetKeys().contains(asset.getAssetKey())
            || asset.getParentKey() != null
            && assetScope.assetKeys().contains(asset.getParentKey())
            || asset.getPageNo() != null
            && assetScope.fallbackPages().contains(asset.getPageNo()))
        .map(asset -> new PaperRecognitionDTO.PaperAssetDTO(
            asset.getAssetKey(),
            asset.getPageNo(),
            asset.getKind(),
            asset.getRole(),
            asset.getAnchorText(),
            asset.getOptionCount(),
            asset.getLayout(),
            asset.getWidth(),
            asset.getHeight()
        ))
        .toList();

    return new PaperRecognitionDTO(
        paperId,
        paper.getRecognitionStatus(),
        paper.getRecognitionError(),
        paper.getPageCount(),
        pageDtos,
        merge == null ? List.of() : merge.questionNos(),
        merge == null ? List.of() : merge.warnings(),
        assetDtos,
        geometryMissingPages,
        paper.getInputStatus(),
        paper.getInputError(),
        paperContentGuard.hasActiveAttempt(paperId)
    );
  }

  private boolean geometryIssueRequiresRecognition(PaperPageEntity page, int assetCount) {
    if (page.getSource() != PageSource.IMAGE || assetCount == 0) {
      return false;
    }
    PageGeometryInspector.Inspection inspection = PageGeometryInspector.inspect(
        page.getSource(), page.getAssetsJson(), assetCount, objectMapper);
    return inspection.issue() == PageGeometryInspector.GeometryIssue.MISSING
        || inspection.issue() == PageGeometryInspector.GeometryIssue.INVALID;
  }

  private AssetScope resolveAssetScope(List<PaperPageEntity> pages) {
    Set<String> assetKeys = new LinkedHashSet<>();
    Set<Integer> fallbackPages = new LinkedHashSet<>();
    for (PaperPageEntity page : pages) {
      if (page.getAssetsJson() == null || page.getAssetsJson().isBlank()) {
        fallbackPages.add(page.getPageNo());
        continue;
      }
      try {
        PageRecognitionGeometrySnapshot snapshot = objectMapper.readValue(
            page.getAssetsJson(), PageRecognitionGeometrySnapshot.class);
        if (snapshot == null || !snapshot.compatible()) {
          fallbackPages.add(page.getPageNo());
          continue;
        }
        snapshot.assets().forEach(asset -> assetKeys.add(asset.assetKey()));
      } catch (JacksonException e) {
        fallbackPages.add(page.getPageNo());
        log.warn("资源预览快照解析失败，回退到页级资源列表: paperId={}, pageNo={}",
            page.getPaperId(), page.getPageNo(), e);
      }
    }
    return new AssetScope(assetKeys, fallbackPages);
  }

  public void updatePageText(Long paperId, int pageNo, String text) {
    paperContentGuard.withPaperLock(paperId, () -> {
      paperContentGuard.assertNoActiveAttempt(paperId);
      PracticePaperEntity paper = requirePaper(paperId);
      assertRecognitionIdle(paper);
      PaperPageEntity page = requirePage(paperId, pageNo);
      PaperFlowLog.event("PAGE_TEXT_UPDATE_BEGIN pageNo={}, chars={}, previousStatus={}",
          pageNo, text == null ? 0 : text.length(), page.getStatus());
      transactionalExecutor.run(() -> {
        page.setTextContent(text == null ? "" : text);
        page.setStatus(PageStatus.READY);
        page.setError(null);
        page.setRecognitionAttemptId(null);
        page.setUpdatedAt(LocalDateTime.now());
        pageRepository.save(page);
        refreshAfterManualEdit(paper);
        resumeRepository.save(paper);
        resumeRepository.incrementContentRevision(paperId, LocalDateTime.now());
      });
      PaperFlowLog.event("PAGE_TEXT_UPDATE_END pageNo={}, recognitionStatus={}",
          pageNo, paper.getRecognitionStatus());
    });
  }

  /**
   * 重试单页。
   *
   * @param forceVision true 表示即使有文字层也强制用识图重做
   */
  public void retryPage(Long paperId, int pageNo, boolean forceVision) {
    paperContentGuard.withPaperLock(paperId, () -> {
      paperContentGuard.assertNoActiveAttempt(paperId);
      PracticePaperEntity paper = requirePaper(paperId);
      assertRecognitionIdle(paper);
      PaperPageEntity page = requirePage(paperId, pageNo);
      PaperFlowLog.event("PAGE_RETRY_BEGIN pageNo={}, forceVision={}, status={}, attempt={}, "
              + "source={}",
          pageNo, forceVision, page.getStatus(), page.getAttempt(), page.getSource());
      if (page.getAttempt() >= MAX_PAGE_ATTEMPTS) {
        PaperFlowLog.warn("PAGE_RETRY_REJECTED pageNo={}, attempt={}, maxAttempts={}",
            pageNo, page.getAttempt(), MAX_PAGE_ATTEMPTS);
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "该页已尝试识别 " + page.getAttempt() + " 次，请重传或删除该页");
      }
      transactionalExecutor.run(() -> {
        page.setStatus(PageStatus.PENDING);
        page.setError(null);
        page.setRecognitionAttemptId(null);
        page.setUpdatedAt(LocalDateTime.now());
        if (forceVision) {
          page.setSource(PageSource.IMAGE);
        }
        pageRepository.save(page);
        paper.setRecognitionStatus(RecognitionStatus.PENDING);
        paper.setRecognitionError(null);
        paper.setRecognitionUpdatedAt(LocalDateTime.now());
        resumeRepository.save(paper);
      });
      if (!recognitionProducer.sendRecognitionTask(paperId)) {
        PaperFlowLog.error("PAGE_RETRY_ENQUEUE_FAILED pageNo={}",
            new BusinessException(ErrorCode.INTERNAL_ERROR, "识别任务投递失败"), pageNo);
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "识别任务投递失败，请稍后重试");
      }
      PaperFlowLog.event("PAGE_RETRY_ENQUEUED pageNo={}, forceVision={}", pageNo, forceVision);
    });
  }

  public void deletePage(Long paperId, int pageNo) {
    paperContentGuard.withPaperLock(paperId, () -> {
      paperContentGuard.assertNoActiveAttempt(paperId);
      PracticePaperEntity paper = requirePaper(paperId);
      assertRecognitionIdle(paper);
      PaperPageEntity page = requirePage(paperId, pageNo);
      PaperFlowLog.event("PAGE_DELETE_BEGIN pageNo={}, status={}, imageKey={}",
          pageNo, page.getStatus(), page.getImageKey());
      if (pageRepository.countByPaperId(paperId) <= 1) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "至少要保留 1 页");
      }
      String imageKey = page.getImageKey();
      transactionalExecutor.run(() -> {
        pageRepository.delete(page);
        pageRepository.shiftPageNumbersUp(paperId, pageNo);
        pageRepository.shiftPageNumbersDown(paperId, pageNo);
        paper.setPageCount((int) pageRepository.countByPaperId(paperId));
        paper.setRecognitionUpdatedAt(LocalDateTime.now());
        refreshAfterManualEdit(paper);
        resumeRepository.save(paper);
        resumeRepository.incrementContentRevision(paperId, LocalDateTime.now());
      });
      try {
        storageService.deleteIfExists(imageKey);
      } catch (Exception e) {
        log.warn("删除页图失败（数据库已删除）: key={}", imageKey, e);
        PaperFlowLog.error("PAGE_IMAGE_DELETE_FAILED key={}", e, imageKey);
      }
      PaperFlowLog.event("PAGE_DELETE_END pageNo={}, pageCount={}",
          pageNo, paper.getPageCount());
    });
  }

  public byte[] getPageImage(Long paperId, int pageNo) {
    requirePaper(paperId);
    PaperPageEntity page = requirePage(paperId, pageNo);
    if (page.getImageKey() == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "该页没有原图");
    }
    return storageService.downloadFile(page.getImageKey());
  }

  public byte[] getAsset(Long paperId, String assetKey) {
    requirePaper(paperId);
    PaperAssetEntity asset = assetService.find(paperId, assetKey)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "资源不存在"));
    return assetService.download(asset);
  }

  /**
   * 结构预览：确认切题前先看层级与材料挂载是否正确。
   */
  public StructurePreviewDTO previewStructure(Long paperId) {
    long startNanos = System.nanoTime();
    try (PaperFlowLog.Scope ignored =
             PaperFlowLog.open("STRUCTURE_PREVIEW", paperId, null, null)) {
      requirePaper(paperId);
      PaperFlowLog.event("STRUCTURE_PREVIEW_BEGIN");
      PaperStructureService.BuiltStructure built = structureService.build(paperId);
      StructurePreviewDTO preview = new StructurePreviewDTO(
          paperId,
          built.subjectId(),
          built.totalScore(),
          built.questions().size(),
          built.warnings(),
          built.paperTree().root().safeChildren().stream()
              .map(this::toPreviewNode)
              .toList()
      );
      PaperFlowLog.json(objectMapper, "STRUCTURE_PREVIEW_RESULT", preview);
      PaperFlowLog.event("STRUCTURE_PREVIEW_END status=SUCCESS, questions={}, totalScore={}, "
              + "warnings={}, durationMs={}",
          built.questions().size(), built.totalScore(), built.warnings(),
          elapsedMs(startNanos));
      return preview;
    } catch (RuntimeException e) {
      PaperFlowLog.error("STRUCTURE_PREVIEW_END status=FAILED, durationMs={}",
          e, elapsedMs(startNanos));
      throw e;
    }
  }

  private StructurePreviewDTO.NodeDTO toPreviewNode(PaperNode node) {
    List<String> assetIds = new ArrayList<>();
    if (node.meta() != null && node.meta().assetIds() != null) {
      assetIds.addAll(node.meta().assetIds());
    }
    if (node.type().equals(PaperNode.TYPE_QUESTION) && node.question() != null
        && node.question().options() != null) {
      assetIds.addAll(node.question().options().stream()
          .map(option -> option.assetId())
          .filter(Objects::nonNull)
          .toList());
    }
    assetIds = assetIds.stream().distinct().toList();
    return new StructurePreviewDTO.NodeDTO(
        node.type(),
        node.title(),
        node.question() != null ? node.question().question() : node.content(),
        node.question() == null ? null : node.question().questionType(),
        node.question() == null ? null : node.question().fullScore(),
        node.question() == null ? null : node.question().sourcePageFrom(),
        node.question() == null ? null : node.question().sourcePageTo(),
        assetIds,
        node.safeChildren().stream().map(this::toPreviewNode).toList()
    );
  }

  /**
   * 确认并切题：合并各页文本 → 写 resume_text → 投递现有切题任务。
   */
  public boolean confirm(Long paperId, boolean ignoreFailed) {
    return confirm(paperId, ignoreFailed, false);
  }

  public boolean confirm(Long paperId, boolean ignoreFailed, boolean force) {
    return paperContentGuard.withPaperLock(
        paperId, () -> confirmLocked(paperId, ignoreFailed, force));
  }

  private boolean confirmLocked(Long paperId, boolean ignoreFailed, boolean force) {
    paperContentGuard.assertNoActiveAttempt(paperId);
    long startNanos = System.nanoTime();
    PracticePaperEntity paper = requirePaper(paperId);
    PaperFlowLog.event("PAPER_CONFIRM_BEGIN recognitionStatus={}, analysisStatus={}, "
            + "ignoreFailed={}, force={}",
        paper.getRecognitionStatus(), paper.getAnalysisStatus(), ignoreFailed, force);
    if (isExtractionInFlight(paper.getRecognitionStatus(), paper.getAnalysisStatus())) {
      PaperFlowLog.event("PAPER_CONFIRM_SKIPPED recognitionStatus={}",
          paper.getRecognitionStatus());
      return false;
    }
    if (paper.getRecognitionStatus() == RecognitionStatus.EXTRACTING) {
      PaperFlowLog.warn("PAPER_CONFIRM_RECOVER_STALE_EXTRACTING analysisStatus={}",
          paper.getAnalysisStatus());
    }
    if (paper.getRecognitionStatus() == RecognitionStatus.PENDING
        || paper.getRecognitionStatus() == RecognitionStatus.PROCESSING) {
      PaperFlowLog.warn("PAPER_CONFIRM_REJECTED reason=RECOGNITION_IN_FLIGHT status={}",
          paper.getRecognitionStatus());
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "识别任务正在处理中，请稍候再切题");
    }
    if (paper.getRecognitionStatus() == RecognitionStatus.READY && !force) {
      PaperFlowLog.event("PAPER_CONFIRM_SKIPPED recognitionStatus={}",
          paper.getRecognitionStatus());
      return false;
    }
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
    boolean hasFailed = pages.stream().anyMatch(page -> page.getStatus() == PageStatus.FAILED);
    if (hasFailed && !ignoreFailed) {
      PaperFlowLog.warn("PAPER_CONFIRM_REJECTED reason=FAILED_PAGES, pageStates={}",
          pageStates(pages));
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "还有识别失败的页，请先重试或删除，或勾选「忽略失败页继续」");
    }
    List<PaperPageEntity> readyPages = pages.stream()
        .filter(page -> page.getStatus() == PageStatus.READY)
        .toList();
    if (readyPages.isEmpty()) {
      PaperFlowLog.warn("PAPER_CONFIRM_REJECTED reason=NO_READY_PAGES");
      throw new BusinessException(ErrorCode.BAD_REQUEST, "没有可切题的内容");
    }
    MergeResult merge = pageTextMerger.mergePlain(readyPages);
    PaperFlowLog.event("PAPER_CONFIRM_MERGE readyPages={}, textChars={}, questionNos={}, "
            + "warnings={}",
        readyPages.size(), merge.text().length(), merge.questionNos(), merge.warnings());
    PaperFlowLog.raw("PAPER_CONFIRM_TEXT paperId=" + paperId, merge.text());
    if (merge.text().isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "各页文本为空，无法切题");
    }

    transactionalExecutor.run(() -> {
      paper.setResumeText(merge.text());
      paper.setPageCount(readyPages.size());
      paper.setRecognitionStatus(RecognitionStatus.EXTRACTING);
      paper.setRecognitionError(null);
      paper.setRecognitionAttemptId(null);
      paper.setRecognitionUpdatedAt(LocalDateTime.now());
      paper.setAnalysisStatus(AsyncTaskStatus.PENDING);
      paper.setAnalysisError(null);
      paper.setAnalysisAttemptId(null);
      paper.setAnalysisUpdatedAt(LocalDateTime.now());
      resumeRepository.save(paper);
      resumeRepository.incrementContentRevision(paperId, LocalDateTime.now());
    });

    if (!extractionProducer.sendExtractTask(paperId)) {
      PaperFlowLog.error("PAPER_CONFIRM_ENQUEUE_FAILED paperId={}",
          new BusinessException(ErrorCode.INTERNAL_ERROR, "切题任务投递失败"), paperId);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "切题任务投递失败，请稍后重试");
    }
    log.info("试卷识别确认完成，已投递切题: paperId={}, pages={}, questions={}",
        paperId, readyPages.size(), merge.questionNos().size());
    PaperFlowLog.event("PAPER_CONFIRM_END status=ENQUEUED, pages={}, questionNos={}, durationMs={}",
        readyPages.size(), merge.questionNos(), elapsedMs(startNanos));
    return true;
  }

  /**
   * 整份重识别：只重跑失败页，或把每一页都重新排队。
   */
  public void retryRecognition(Long paperId, boolean allPages) {
    paperContentGuard.withPaperLock(
        paperId, () -> retryRecognitionLocked(paperId, allPages));
  }

  private void retryRecognitionLocked(Long paperId, boolean allPages) {
    paperContentGuard.assertNoActiveAttempt(paperId);
    PracticePaperEntity paper = requirePaper(paperId);
    assertRecognitionIdle(paper);
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
    PaperFlowLog.event("RECOGNITION_RETRY_BEGIN allPages={}, pageStates={}",
        allPages, pageStates(pages));
    if (pages.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "试卷没有可识别的页");
    }
    transactionalExecutor.run(() -> {
      for (PaperPageEntity page : pages) {
        if (allPages || page.getStatus() == PageStatus.FAILED) {
          page.setStatus(PageStatus.PENDING);
          page.setError(null);
          page.setUpdatedAt(LocalDateTime.now());
          pageRepository.save(page);
        }
      }
      paper.setRecognitionStatus(RecognitionStatus.PENDING);
      paper.setRecognitionError(null);
      paper.setRecognitionAttemptId(null);
      paper.setRecognitionUpdatedAt(LocalDateTime.now());
      resumeRepository.save(paper);
    });
    resumeRepository.resetRecognitionRecoveryCount(paperId);
    if (!recognitionProducer.sendRecognitionTask(paperId)) {
      PaperFlowLog.error("RECOGNITION_RETRY_ENQUEUE_FAILED allPages={}",
          new BusinessException(ErrorCode.INTERNAL_ERROR, "识别任务投递失败"), allPages);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "识别任务投递失败，请稍后重试");
    }
    PaperFlowLog.event("RECOGNITION_RETRY_ENQUEUED allPages={}, pageStates={}",
        allPages, pageStates(pageRepository.findByPaperIdOrderByPageNoAsc(paperId)));
  }

  // ==================== 内部 ====================

  /**
   * 识别或切题执行期间禁止再次修改页内容 / 重投识别任务，避免重复点击叠加任务。
   */
  private void assertRecognitionIdle(PracticePaperEntity paper) {
    if (paper.getRecognitionStatus() == RecognitionStatus.PENDING
        || paper.getRecognitionStatus() == RecognitionStatus.PROCESSING) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "识别任务正在处理中，请稍候再试");
    }
    if (paper.getRecognitionStatus() == RecognitionStatus.EXTRACTING) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "切题任务正在处理中，请稍候再试");
    }
  }

  static boolean isExtractionInFlight(RecognitionStatus recognitionStatus,
                                      AsyncTaskStatus analysisStatus) {
    return recognitionStatus == RecognitionStatus.EXTRACTING
        && (analysisStatus == AsyncTaskStatus.PENDING
        || analysisStatus == AsyncTaskStatus.PROCESSING);
  }

  private void refreshAfterManualEdit(PracticePaperEntity paper) {
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paper.getId());
    boolean anyFailed = pages.stream().anyMatch(page -> page.getStatus() == PageStatus.FAILED);
    boolean anyPending = pages.stream().anyMatch(page -> page.getStatus() == PageStatus.PENDING);
    if (!anyFailed && !anyPending
        && (paper.getRecognitionStatus() == RecognitionStatus.FAILED
        || paper.getRecognitionStatus() == RecognitionStatus.PROCESSING)) {
      paper.setRecognitionStatus(RecognitionStatus.NEEDS_REVIEW);
      paper.setRecognitionError(null);
      paper.setRecognitionUpdatedAt(LocalDateTime.now());
    }
  }

  private void heartbeat(Long paperId, String attemptId) {
    int updated = resumeRepository.heartbeatRecognitionProcessing(paperId, attemptId, LocalDateTime.now());
    if (updated != 1) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "试卷识别任务执行权已失效");
    }
  }

  private List<MultipartFile> validateFiles(List<MultipartFile> files) {
    if (files == null || files.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要上传的试卷文件");
    }
    if (files.size() > MAX_FILES) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "一次最多上传 " + MAX_FILES + " 个文件");
    }
    for (MultipartFile file : files) {
      if (file == null || file.isEmpty()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "存在空文件");
      }
      byte[] bytes = readBytes(file);
      String contentType = contentTypeDetectionService.detectContentType(bytes, file.getOriginalFilename());
      String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
      boolean pdf = isPdf(contentType) || name.endsWith(".pdf");
      boolean docx = (contentType != null && contentType.toLowerCase().contains("wordprocessingml"))
          || name.endsWith(".docx");
      boolean doc = (contentType != null && contentType.toLowerCase().contains("msword"))
          || name.endsWith(".doc");
      boolean image = IMAGE_CONTENT_TYPES.containsKey(contentType)
          || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
      boolean text = (contentType != null && contentType.toLowerCase().startsWith("text/"))
          || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown");
      if (!pdf && !docx && !doc && !image && !text) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            file.getOriginalFilename()
                + " 不是支持的格式（PDF / DOCX / DOC / TXT / MD / JPG / PNG）");
      }
      long limit = image ? MAX_IMAGE_SIZE : MAX_DOCUMENT_SIZE;
      if (file.getSize() > limit) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            file.getOriginalFilename() + " 超过 " + (limit / 1024 / 1024) + "MB，请压缩或拆分后再上传");
      }
    }
    return files;
  }

  void chargePageQuota(Long ownerId, int pages) {
    String ownerKey = ownerId != null ? ownerId.toString() : "anonymous";
    String key = QUOTA_KEY_PREFIX + ownerKey + ":" + LocalDate.now().format(DAY_FORMAT);
    long current = redisService.getAtomicLong(key).addAndGet(pages);
    if (current == pages) {
      redisService.expire(key, Duration.ofDays(2));
    }
    if (current > DAILY_PAGE_QUOTA) {
      redisService.getAtomicLong(key).addAndGet(-pages);
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "今日识别页数已用完（上限 " + DAILY_PAGE_QUOTA + " 页/天），请明天再试");
    }
  }

  private PracticePaperEntity requirePaper(Long paperId) {
    return resumeRepository.findByIdScoped(paperId, ownerScope.effectiveOwnerId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "试卷不存在或无权访问"));
  }

  private PaperPageEntity requirePage(Long paperId, int pageNo) {
    return pageRepository.findByPaperIdAndPageNo(paperId, pageNo)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "第 " + pageNo + " 页不存在"));
  }

  private void cleanup(Long paperId, List<String> uploadedKeys) {
    for (String key : uploadedKeys) {
      try {
        storageService.deleteIfExists(key);
      } catch (Exception e) {
        log.warn("清理页图失败: key={}", key, e);
        PaperFlowLog.error("UPLOAD_CLEANUP_IMAGE_FAILED key={}", e, key);
      }
    }
    try {
      assetService.deleteByPaperId(paperId);
      pageRepository.deleteByPaperId(paperId);
      resumeRepository.deleteById(paperId);
    } catch (Exception e) {
      log.warn("清理识别失败的上传记录失败: paperId={}", paperId, e);
      PaperFlowLog.error("UPLOAD_CLEANUP_DATABASE_FAILED paperId={}", e, paperId);
    }
  }

  /**
   * 输入处理失败时只清理本次已生成的页 / 页图 / 资源，保留原始文件与试卷壳记录。
   */
  void cleanupInputArtifacts(Long paperId, List<String> uploadedKeys) {
    Set<String> imageKeys = new LinkedHashSet<>(uploadedKeys);
    pageRepository.findByPaperIdOrderByPageNoAsc(paperId).stream()
        .map(PaperPageEntity::getImageKey)
        .filter(Objects::nonNull)
        .forEach(imageKeys::add);
    for (String key : imageKeys) {
      try {
        storageService.deleteIfExists(key);
      } catch (Exception e) {
        log.warn("清理输入处理页图失败: key={}", key, e);
        PaperFlowLog.error("INPUT_CLEANUP_IMAGE_FAILED key={}", e, key);
      }
    }
    try {
      assetService.deleteByPaperId(paperId);
      pageRepository.deleteByPaperId(paperId);
    } catch (Exception e) {
      log.warn("清理输入处理页资源失败: paperId={}", paperId, e);
      PaperFlowLog.error("INPUT_CLEANUP_DATABASE_FAILED paperId={}", e, paperId);
    }
  }

  private boolean isPdf(String contentType) {
    return contentType != null && contentType.toLowerCase().contains("pdf");
  }

  private record AssetScope(Set<String> assetKeys, Set<Integer> fallbackPages) {
  }

  private byte[] readBytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      PaperFlowLog.error("PAPER_FILE_READ_FAILED name={}, bytes={}",
          e, file == null ? null : file.getOriginalFilename(),
          file == null ? 0 : file.getSize());
      throw new BusinessException(ErrorCode.BAD_REQUEST, "读取文件失败", e);
    }
  }

  private String appendNote(String notes, String extra) {
    if (notes == null || notes.isBlank()) {
      return extra;
    }
    return notes + "；" + extra;
  }

  private String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }

  private void failOnStemFigureMismatch(
      PaperPageEntity page,
      String pageAttemptId,
      List<String> stemFigureAnchors,
      List<PageRecognition.PageAsset> resolvedAssets
  ) {
    Map<String, StemFigureVerdict> verdicts =
        PaperStemFigureConsistency.check(stemFigureAnchors, resolvedAssets);
    String failure = PaperStemFigureConsistency.failureMessage(page.getPageNo(), verdicts);
    PaperFlowLog.event("STEM_FIGURE_CONSISTENCY pageNo={}, declaredAnchors={}, "
            + "resolvedStemAnchors={}, verdicts={}, failure={}",
        page.getPageNo(), stemFigureAnchors,
        resolvedAssets.stream()
            .filter(asset -> asset != null
                && "FIGURE".equalsIgnoreCase(asset.kind())
                && "STEM".equalsIgnoreCase(asset.role()))
            .map(PageRecognition.PageAsset::anchorText)
            .toList(),
        verdicts, failure);
    if (failure == null) {
      return;
    }
    PageRecognitionDiagnostic diagnostic = PaperStemFigureConsistency.diagnostic(
        "INCOMPLETE_CONTENT", page.getPageNo(), stemFigureAnchors, resolvedAssets, verdicts);
    PaperFlowLog.json(objectMapper, "PAGE_RECOGNITION_DIAGNOSTIC", diagnostic);
    writePageDiagnostic(page, pageAttemptId, diagnostic);
    log.warn("试卷页题干图一致性校验失败: paperId={}, pageNo={}, verdicts={}",
        page.getPaperId(), page.getPageNo(), verdictSummary(verdicts));
    PaperFlowLog.error("STEM_FIGURE_MISMATCH pageNo={}, anchors={}, assets={}, verdicts={}",
        new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED, failure),
        page.getPageNo(), stemFigureAnchors, resolvedAssets, verdicts);
    throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED, failure);
  }

  private void writePageDiagnostic(
      PaperPageEntity page,
      String pageAttemptId,
      PageRecognitionDiagnostic diagnostic
  ) {
    try {
      int updated = pageRepository.writePageDiagnostic(
          page.getPaperId(), page.getPageNo(), pageAttemptId,
          objectMapper.writeValueAsString(diagnostic), LocalDateTime.now());
      PaperFlowLog.event("PAGE_DIAGNOSTIC_STORED pageNo={}, attemptId={}, updated={}",
          page.getPageNo(), pageAttemptId, updated);
    } catch (JacksonException e) {
      log.warn("页识别诊断序列化失败，跳过落库: paperId={}, pageNo={}",
          page.getPaperId(), page.getPageNo(), e);
      PaperFlowLog.error("PAGE_DIAGNOSTIC_STORE_FAILED pageNo={}, attemptId={}",
          e, page.getPageNo(), pageAttemptId);
    }
  }

  private String verdictSummary(Map<String, StemFigureVerdict> verdicts) {
    if (verdicts == null || verdicts.isEmpty()) {
      return "{}";
    }
    return verdicts.entrySet().stream()
        .map(entry -> truncate(entry.getKey(), 24) + "=" + entry.getValue())
        .collect(Collectors.joining(", ", "{", "}"));
  }

  private int safePageCount(PracticePaperEntity paper) {
    if (paper.getPageCount() != null) {
      return paper.getPageCount();
    }
    return (int) pageRepository.countByPaperId(paper.getId());
  }
}
