package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.resume.listener.PaperExtractionStreamProducer;
import interview.guide.modules.resume.listener.PaperRecognitionStreamProducer;
import interview.guide.modules.resume.document.AssetKind;
import interview.guide.modules.resume.document.AssetRole;
import interview.guide.modules.resume.document.DocAsset;
import interview.guide.modules.resume.document.DocumentParser;
import interview.guide.modules.resume.document.ParsedDocument;
import interview.guide.modules.resume.document.ParsedPage;
import interview.guide.modules.resume.model.PageRecognition;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.model.PaperRecognitionDTO;
import interview.guide.modules.resume.model.PaperUploadResult;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.model.StructurePreviewDTO;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.PageTextMerger.MergeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

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
  public static final int MAX_PAGES = 10;
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
  private final PageTextMerger pageTextMerger;
  private final List<DocumentParser> documentParsers;
  private final PaperAssetService assetService;
  private final PaperStructureService structureService;
  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PaperExtractionStreamProducer extractionProducer;
  private final PaperRecognitionStreamProducer recognitionProducer;
  private final RedisService redisService;
  private final TransactionalExecutor transactionalExecutor;
  private final BeanOutputConverter<PageRecognition> outputConverter;
  private final String systemPrompt;

  public PaperRecognitionService(
      OwnerScope ownerScope,
      ResumeRepository resumeRepository,
      PaperPageRepository pageRepository,
      FileStorageService storageService,
      FileHashService fileHashService,
      ContentTypeDetectionService contentTypeDetectionService,
      PdfPageRenderer pdfPageRenderer,
      PageImageNormalizer imageNormalizer,
      PageTextMerger pageTextMerger,
      List<DocumentParser> documentParsers,
      PaperAssetService assetService,
      PaperStructureService structureService,
      LlmProviderRegistry llmProviderRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      PaperExtractionStreamProducer extractionProducer,
      PaperRecognitionStreamProducer recognitionProducer,
      RedisService redisService,
      TransactionalExecutor transactionalExecutor,
      ResourceLoader resourceLoader
  ) throws IOException {
    this.ownerScope = ownerScope;
    this.resumeRepository = resumeRepository;
    this.pageRepository = pageRepository;
    this.storageService = storageService;
    this.fileHashService = fileHashService;
    this.contentTypeDetectionService = contentTypeDetectionService;
    this.pdfPageRenderer = pdfPageRenderer;
    this.imageNormalizer = imageNormalizer;
    this.pageTextMerger = pageTextMerger;
    this.documentParsers = documentParsers;
    this.assetService = assetService;
    this.structureService = structureService;
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.extractionProducer = extractionProducer;
    this.recognitionProducer = recognitionProducer;
    this.redisService = redisService;
    this.transactionalExecutor = transactionalExecutor;
    this.outputConverter = new BeanOutputConverter<>(PageRecognition.class);
    this.systemPrompt = resourceLoader
        .getResource("classpath:prompts/paper-page-recognition-system.st")
        .getContentAsString(StandardCharsets.UTF_8)
        + "\n\n" + outputConverter.getFormat();
  }

  // ==================== 上传 ====================

  /**
   * 上传 1~10 个 PDF / JPG / PNG，合并为同一份试卷并投递识别任务。
   */
  public PaperUploadResult upload(List<MultipartFile> files) {
    List<MultipartFile> validFiles = validateFiles(files);

    int totalPages = 0;
    List<String> hashes = new ArrayList<>();
    List<ParsedDocument> parsedDocuments = new ArrayList<>();
    for (MultipartFile file : validFiles) {
      byte[] bytes = readBytes(file);
      String contentType = contentTypeDetectionService.detectContentType(bytes, file.getOriginalFilename());
      ParsedDocument parsed = findParser(contentType, file.getOriginalFilename())
          .parse(bytes, file.getOriginalFilename(), contentType);
      parsedDocuments.add(parsed);
      totalPages += parsed.pages().size();
      if (totalPages > MAX_PAGES) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "单份试卷最多 " + MAX_PAGES + " 页，当前共 " + totalPages + " 页");
      }
      hashes.add(fileHashService.calculateHash(bytes));
    }

    String fileHash = hashes.size() == 1
        ? hashes.get(0)
        : fileHashService.calculateHash(String.join(":", hashes).getBytes(StandardCharsets.UTF_8));

    Optional<PracticePaperEntity> existing =
        resumeRepository.findByFileHashScoped(fileHash, ownerScope.effectiveOwnerId());
    if (existing.isPresent()) {
      PracticePaperEntity paper = existing.get();
      log.info("检测到重复试卷，直接返回现有记录: paperId={}", paper.getId());
      return new PaperUploadResult(paper.getId(), safePageCount(paper),
          paper.getRecognitionStatus(), true, "检测到相同文件，已返回现有试卷");
    }

    chargePageQuota(totalPages);
    int pageTotal = totalPages;

    PracticePaperEntity paper = transactionalExecutor.call(() -> {
      PracticePaperEntity entity = new PracticePaperEntity();
      entity.setFileHash(fileHash);
      entity.setOwnerId(ownerScope.currentUserId());
      entity.setOriginalFilename(validFiles.size() == 1
          ? validFiles.get(0).getOriginalFilename()
          : "多页试卷（" + validFiles.size() + " 个文件）");
      entity.setSourceType(resolveSourceType(parsedDocuments));
      entity.setFileSize(validFiles.stream().mapToLong(MultipartFile::getSize).sum());
      entity.setContentType(validFiles.get(0).getContentType());
      entity.setResumeText(null);
      entity.setPageCount(pageTotal);
      entity.setRecognitionStatus(RecognitionStatus.PENDING);
      entity.setRecognitionUpdatedAt(LocalDateTime.now());
      entity.setAnalysisStatus(AsyncTaskStatus.PENDING);
      entity.setAnalysisUpdatedAt(LocalDateTime.now());
      return resumeRepository.save(entity);
    });

    List<String> uploadedKeys = new ArrayList<>();
    try {
      createPages(paper, parsedDocuments, uploadedKeys);
    } catch (RuntimeException e) {
      cleanup(paper.getId(), uploadedKeys);
      throw e;
    }

    if (!recognitionProducer.sendRecognitionTask(paper.getId())) {
      transactionalExecutor.run(() -> resumeRepository.failRecognitionIfPending(
          paper.getId(), "识别任务投递失败，请稍后重试", LocalDateTime.now()));
      return new PaperUploadResult(paper.getId(), totalPages, RecognitionStatus.FAILED, false,
          "试卷已保存，但识别任务投递失败，请稍后重试");
    }

    return new PaperUploadResult(paper.getId(), totalPages, RecognitionStatus.PENDING, false, "");
  }

  private void createPages(PracticePaperEntity paper, List<ParsedDocument> documents,
                           List<String> uploadedKeys) {
    List<PaperPageEntity> pages = new ArrayList<>();
    int pageOffset = 0;
    for (ParsedDocument document : documents) {
      for (ParsedPage parsed : document.pages()) {
        int pageNo = ++pageOffset;
        String imageKey = null;
        if (parsed.pageImage() != null && parsed.pageImage().length > 0) {
          imageKey = storageService.uploadPageImage(paper.getId(), pageNo, parsed.pageImage());
          uploadedKeys.add(imageKey);
        }
        PaperPageEntity page = new PaperPageEntity();
        page.setPaperId(paper.getId());
        page.setPageNo(pageNo);
        page.setSource(parsed.source());
        page.setImageKey(imageKey);
        page.setLogicalPage(parsed.logicalPage());
        page.setUpdatedAt(LocalDateTime.now());
        if (parsed.source() == PageSource.TEXT) {
          page.setTextContent(String.join("\n", parsed.lines()));
          page.setStatus(PageStatus.READY);
        } else {
          page.setStatus(PageStatus.PENDING);
        }
        pages.add(page);
        if (!parsed.assets().isEmpty()) {
          assetService.saveAll(paper.getId(), pageNo, parsed.assets(), null);
        }
      }
    }
    transactionalExecutor.run(() -> pageRepository.saveAll(pages));
  }

  private DocumentParser findParser(String contentType, String filename) {
    return documentParsers.stream()
        .filter(parser -> parser.supports(contentType, filename))
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
            "不支持的文件类型：" + (contentType == null ? filename : contentType)));
  }

  private String resolveSourceType(List<ParsedDocument> documents) {
    for (ParsedDocument document : documents) {
      if ("DOCX".equals(document.sourceType()) || "DOC".equals(document.sourceType())) {
        return document.sourceType();
      }
    }
    return documents.isEmpty() ? "PDF" : documents.get(0).sourceType();
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
      return;
    }
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
    if (pages.isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED, "试卷没有可识别的页");
    }

    ChatClient visionClient = null;
    for (PaperPageEntity page : pages) {
      if (page.getStatus() != PageStatus.PENDING) {
        continue;
      }
      heartbeat(paperId, attemptId);
      if (page.getSource() == PageSource.TEXT) {
        markReady(page, page.getTextContent(), false, null);
        continue;
      }
      if (visionClient == null) {
        visionClient = llmProviderRegistry.getDefaultVisionChatClient();
      }
      try {
        recognizePage(page, visionClient, paper.getPageCount());
      } catch (Exception e) {
        markFailed(page, e);
      }
    }

    long failed = pages.stream().filter(p -> p.getStatus() == PageStatus.FAILED).count();
    if (failed == pages.size()) {
      throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
          "所有页均识别失败，请检查文件清晰度或识图模型配置");
    }
  }

  private void recognizePage(PaperPageEntity page, ChatClient visionClient, Integer pageCount) {
    long start = System.currentTimeMillis();
    String model = llmProviderRegistry.getDefaultVisionModelName();
    log.info("开始识别试卷页: paperId={}, pageNo={}, model={}", page.getPaperId(), page.getPageNo(), model);
    byte[] jpeg = storageService.downloadFile(page.getImageKey());
    Media media = new Media(MimeTypeUtils.IMAGE_JPEG,
        new ByteArrayResource(jpeg, "page-" + page.getPageNo() + ".jpg"));
    String userPrompt = "这是第 " + page.getPageNo() + " 页（共 " + (pageCount != null ? pageCount : 1)
        + " 页）。请转写这一页的全部印刷体内容。";

    PageRecognition result = structuredOutputInvoker.invoke(
        visionClient,
        systemPrompt,
        userPrompt,
        List.of(media),
        outputConverter,
        ErrorCode.PAPER_RECOGNITION_FAILED,
        "第 " + page.getPageNo() + " 页识别失败：",
        "试卷识别-第" + page.getPageNo() + "页",
        log
    );

    String text = result.text() == null ? "" : result.text().strip();
    String notes = result.notes();
    if (text.isEmpty()) {
      notes = appendNote(notes, "本页未识别到文字");
    } else if (text.length() < 20 && !result.hasFigure()) {
      notes = appendNote(notes, "识别内容较少，请人工核对");
    }
    page.setModel(model);
    saveRecognizedAssets(page, jpeg, result.assets());
    markReady(page, text, result.hasFigure(), notes);
    log.info("试卷页识别完成: paperId={}, pageNo={}, chars={}, durationMs={}",
        page.getPaperId(), page.getPageNo(), text.length(), System.currentTimeMillis() - start);
  }

  /**
   * 视觉模型只给归一化 bbox；这里按 bbox 从页图裁出资源，落 S3 + paper_assets。
   */
  private void saveRecognizedAssets(PaperPageEntity page, byte[] pageJpeg,
                                    List<PageRecognition.PageAsset> assets) {
    if (assets == null || assets.isEmpty()) {
      return;
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(pageJpeg));
      if (image == null) {
        return;
      }
      List<DocAsset> docAssets = new ArrayList<>();
      int index = 0;
      for (PageRecognition.PageAsset asset : assets) {
        index++;
        double[] bbox = asset.bbox();
        if (bbox == null || bbox.length < 4) {
          continue;
        }
        int x = (int) Math.round(Math.max(0, bbox[0]) * image.getWidth());
        int y = (int) Math.round(Math.max(0, bbox[1]) * image.getHeight());
        int w = (int) Math.round(Math.min(1 - bbox[0], bbox[2]) * image.getWidth());
        int h = (int) Math.round(Math.min(1 - bbox[1], bbox[3]) * image.getHeight());
        if (w < 10 || h < 10 || x + w > image.getWidth() || y + h > image.getHeight()) {
          continue;
        }
        BufferedImage crop = image.getSubimage(x, y, w, h);
        byte[] bytes = imageNormalizer.toJpeg(crop);
        String key = "p" + page.getPageNo() + "a" + index;
        AssetKind kind;
        try {
          kind = AssetKind.valueOf(asset.kind() == null ? "FIGURE" : asset.kind().toUpperCase());
        } catch (Exception e) {
          kind = AssetKind.FIGURE;
        }
        AssetRole role;
        try {
          role = AssetRole.valueOf(asset.role() == null ? "STEM" : asset.role().toUpperCase());
        } catch (Exception e) {
          role = AssetRole.STEM;
        }
        docAssets.add(new DocAsset(key, kind, role, bytes, asset.anchorText(),
            asset.layout(), asset.optionCount(), asset.labels(), bbox, w, h));
      }
      assetService.saveAll(page.getPaperId(), page.getPageNo(), docAssets, null);
    } catch (Exception e) {
      log.warn("保存页资源失败: paperId={}, pageNo={}, error={}",
          page.getPaperId(), page.getPageNo(), e.getMessage());
    }
  }

  private void markReady(PaperPageEntity page, String text, boolean hasFigure, String notes) {
    transactionalExecutor.run(() -> {
      page.setTextContent(text == null ? "" : text);
      page.setHasFigure(hasFigure);
      page.setNotes(truncate(notes, 500));
      page.setStatus(PageStatus.READY);
      page.setError(null);
      page.setAttempt(page.getAttempt() + 1);
      page.setUpdatedAt(LocalDateTime.now());
      pageRepository.save(page);
    });
  }

  private void markFailed(PaperPageEntity page, Exception error) {
    log.warn("试卷页识别失败: paperId={}, pageNo={}, error={}",
        page.getPaperId(), page.getPageNo(), ErrorLogSanitizer.summarize(error));
    transactionalExecutor.run(() -> {
      page.setStatus(PageStatus.FAILED);
      page.setError(truncate(ErrorLogSanitizer.summarize(error), 500));
      page.setAttempt(page.getAttempt() + 1);
      page.setUpdatedAt(LocalDateTime.now());
      pageRepository.save(page);
    });
  }

  // ==================== 查询 / 人工校对 ====================

  public PaperRecognitionDTO getRecognition(Long paperId) {
    PracticePaperEntity paper = requirePaper(paperId);
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
    List<PaperPageEntity> readyPages = pages.stream()
        .filter(page -> page.getStatus() == PageStatus.READY)
        .toList();
    MergeResult merge = readyPages.isEmpty() ? null : pageTextMerger.merge(readyPages);

    List<PaperRecognitionDTO.PaperPageDTO> pageDtos = pages.stream()
        .map(page -> new PaperRecognitionDTO.PaperPageDTO(
            page.getPageNo(),
            page.getSource(),
            page.getStatus(),
            page.getTextContent(),
            page.isHasFigure(),
            page.getNotes(),
            page.getError(),
            "/api/papers/" + paperId + "/pages/" + page.getPageNo() + "/image"
        ))
        .toList();

    List<PaperRecognitionDTO.PaperAssetDTO> assetDtos = assetService.list(paperId).stream()
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
        assetDtos
    );
  }

  public void updatePageText(Long paperId, int pageNo, String text) {
    PracticePaperEntity paper = requirePaper(paperId);
    PaperPageEntity page = requirePage(paperId, pageNo);
    transactionalExecutor.run(() -> {
      page.setTextContent(text == null ? "" : text);
      page.setStatus(PageStatus.READY);
      page.setError(null);
      page.setUpdatedAt(LocalDateTime.now());
      pageRepository.save(page);
      refreshAfterManualEdit(paper);
      resumeRepository.save(paper);
    });
  }

  /**
   * 重试单页。
   *
   * @param forceVision true 表示即使有文字层也强制用识图重做
   */
  public void retryPage(Long paperId, int pageNo, boolean forceVision) {
    PracticePaperEntity paper = requirePaper(paperId);
    PaperPageEntity page = requirePage(paperId, pageNo);
    if (page.getAttempt() >= MAX_PAGE_ATTEMPTS) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "该页已尝试识别 " + page.getAttempt() + " 次，请重传或删除该页");
    }
    transactionalExecutor.run(() -> {
      page.setStatus(PageStatus.PENDING);
      page.setError(null);
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
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "识别任务投递失败，请稍后重试");
    }
  }

  public void deletePage(Long paperId, int pageNo) {
    PracticePaperEntity paper = requirePaper(paperId);
    PaperPageEntity page = requirePage(paperId, pageNo);
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
    });
    try {
      storageService.deleteIfExists(imageKey);
    } catch (Exception e) {
      log.warn("删除页图失败（数据库已删除）: key={}, error={}", imageKey,
          ErrorLogSanitizer.summarize(e));
    }
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
    requirePaper(paperId);
    PaperStructureService.BuiltStructure built = structureService.build(paperId);
    return new StructurePreviewDTO(
        paperId,
        built.subjectId(),
        built.totalScore(),
        built.questions().size(),
        built.warnings(),
        built.paperTree().root().safeChildren().stream()
            .map(this::toPreviewNode)
            .toList()
    );
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
  public void confirm(Long paperId, boolean ignoreFailed) {
    PracticePaperEntity paper = requirePaper(paperId);
    if (paper.getRecognitionStatus() == RecognitionStatus.EXTRACTING
        || paper.getRecognitionStatus() == RecognitionStatus.READY) {
      return;
    }
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
    boolean hasFailed = pages.stream().anyMatch(page -> page.getStatus() == PageStatus.FAILED);
    if (hasFailed && !ignoreFailed) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "还有识别失败的页，请先重试或删除，或勾选「忽略失败页继续」");
    }
    List<PaperPageEntity> readyPages = pages.stream()
        .filter(page -> page.getStatus() == PageStatus.READY)
        .toList();
    if (readyPages.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "没有可切题的内容");
    }
    MergeResult merge = pageTextMerger.mergePlain(readyPages);
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
    });

    if (!extractionProducer.sendExtractTask(paperId)) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "切题任务投递失败，请稍后重试");
    }
    log.info("试卷识别确认完成，已投递切题: paperId={}, pages={}, questions={}",
        paperId, readyPages.size(), merge.questionNos().size());
  }

  /**
   * 整份重识别：只重跑失败页，或把每一页都重新排队。
   */
  public void retryRecognition(Long paperId, boolean allPages) {
    PracticePaperEntity paper = requirePaper(paperId);
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
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
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "识别任务投递失败，请稍后重试");
    }
  }

  // ==================== 内部 ====================

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
      if (!pdf && !docx && !doc && !image) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            file.getOriginalFilename() + " 不是支持的格式（PDF / DOCX / DOC / JPG / PNG）");
      }
      long limit = image ? MAX_IMAGE_SIZE : MAX_DOCUMENT_SIZE;
      if (file.getSize() > limit) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            file.getOriginalFilename() + " 超过 " + (limit / 1024 / 1024) + "MB，请压缩或拆分后再上传");
      }
    }
    return files;
  }

  private void chargePageQuota(int pages) {
    String key = QUOTA_KEY_PREFIX + ownerScope.currentUserId() + ":" + LocalDate.now().format(DAY_FORMAT);
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
        log.warn("清理页图失败: key={}, error={}", key, ErrorLogSanitizer.summarize(e));
      }
    }
    try {
      assetService.deleteByPaperId(paperId);
      pageRepository.deleteByPaperId(paperId);
      resumeRepository.deleteById(paperId);
    } catch (Exception e) {
      log.warn("清理识别失败的上传记录失败: paperId={}", paperId, e);
    }
  }

  private boolean isPdf(String contentType) {
    return contentType != null && contentType.toLowerCase().contains("pdf");
  }

  private byte[] readBytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "读取文件失败");
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

  private int safePageCount(PracticePaperEntity paper) {
    if (paper.getPageCount() != null) {
      return paper.getPageCount();
    }
    return (int) pageRepository.countByPaperId(paper.getId());
  }
}
