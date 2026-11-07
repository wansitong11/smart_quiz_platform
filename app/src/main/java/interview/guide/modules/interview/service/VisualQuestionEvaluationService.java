package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.VisionFailureClass;
import interview.guide.common.ai.VisionFailureClassifier;
import interview.guide.common.ai.VisionRequestRejectedException;
import interview.guide.common.ai.VisionRetryPolicy;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.service.PaperAssetService;
import interview.guide.modules.resume.service.PageImageNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 依赖图形的主观题批改：把原图和学生文字描述一起交给视觉模型。
 */
@Slf4j
@Service
public class VisualQuestionEvaluationService {

  private static final int MAX_IMAGES = 10;
  private static final int MAX_VISUAL_GRADE_ATTEMPTS = 2;

  private final LlmProviderRegistry providerRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PaperAssetService assetService;
  private final PaperPageRepository pageRepository;
  private final FileStorageService storageService;
  private final PageImageNormalizer imageNormalizer;
  private final PromptTemplate systemPrompt;
  private final PromptTemplate userPrompt;
  private final BeanOutputConverter<VisualGradeDTO> outputConverter =
      new BeanOutputConverter<>(VisualGradeDTO.class);

  public VisualQuestionEvaluationService(
      LlmProviderRegistry providerRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      PaperAssetService assetService,
      PaperPageRepository pageRepository,
      FileStorageService storageService,
      PageImageNormalizer imageNormalizer,
      ResourceLoader resourceLoader
  ) throws IOException {
    this.providerRegistry = providerRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.assetService = assetService;
    this.pageRepository = pageRepository;
    this.storageService = storageService;
    this.imageNormalizer = imageNormalizer;
    this.systemPrompt = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-visual-question-evaluation-system.st"));
    this.userPrompt = new PromptTemplate(read(resourceLoader,
        "classpath:prompts/paper-visual-question-evaluation-user.st"));
  }

  public record VisualGradeDTO(
      int questionIndex,
      int score,
      String feedback,
      boolean needsManualReview,
      Integer confidence
  ) {
    public static VisualGradeDTO unanswered(int questionIndex) {
      return new VisualGradeDTO(questionIndex, 0, "未作答。", false, 100);
    }

    public static VisualGradeDTO manualReview(int questionIndex, String message) {
      return new VisualGradeDTO(questionIndex, 0, message, true, 0);
    }
  }

  private record LoadedMedia(List<Media> media, String guide) {
  }

  public VisualGradeDTO grade(Long paperId, InterviewQuestionDTO question,
                              List<String> assetIds) {
    if (question.userAnswer() == null || question.userAnswer().isBlank()) {
      return VisualGradeDTO.unanswered(question.questionIndex());
    }

    LoadedMedia loadedMedia = loadMedia(paperId, question, assetIds);
    if (loadedMedia.media().isEmpty()) {
      return VisualGradeDTO.manualReview(question.questionIndex(),
          "题目图片不可用，无法自动判断，请在报告中人工复核。");
    }

    String keyPoints = question.keyPoints() == null || question.keyPoints().isEmpty()
        ? "" : String.join("；", question.keyPoints());
    String userContent = userPrompt.render(Map.of(
        "question", nullToEmpty(question.question()),
        "figureSummary", nullToEmpty(question.visualContext()),
        "referenceAnswer", nullToEmpty(question.referenceAnswer()),
        "keyPoints", keyPoints,
        "userAnswer", question.userAnswer(),
        "images", loadedMedia.guide()
    ));
    log.info("图形题批改输入: paperId={}, questionIndex={}, images={}, page={}",
        paperId, question.questionIndex(), loadedMedia.media().size(),
        question.sourcePageFrom());
    VisualGradeDTO result = invokeVisualGrade(question, userContent, loadedMedia.media());
    String feedback = nullToEmpty(result.feedback()).isBlank()
        ? "已完成图形题批改。" : limitText(result.feedback(), 600);
    return new VisualGradeDTO(
        question.questionIndex(),
        Math.max(0, Math.min(100, result.score())),
        feedback,
        result.needsManualReview(),
        result.confidence() == null ? 0 : Math.max(0, Math.min(100, result.confidence()))
    );
  }

  private VisualGradeDTO invokeVisualGrade(
      InterviewQuestionDTO question,
      String userContent,
      List<Media> media
  ) {
    for (int attempt = 1; attempt <= MAX_VISUAL_GRADE_ATTEMPTS; attempt++) {
      try {
        return structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
            providerRegistry.getDefaultVisionChatClient(),
            systemPrompt.render() + "\n\n" + outputConverter.getFormat(),
            userContent,
            media,
            outputConverter,
            ErrorCode.INTERVIEW_EVALUATION_FAILED,
            "图形主观题批改失败：",
            "图形题批改-" + question.questionIndex(),
            log
        );
      } catch (VisionRequestRejectedException e) {
        throw e;
      } catch (BusinessException e) {
        VisionFailureClass failureClass = VisionFailureClassifier.classify(e);
        if (!VisionRetryPolicy.shouldRetry(
            failureClass, attempt, MAX_VISUAL_GRADE_ATTEMPTS)) {
          throw e;
        }
        if (failureClass == VisionFailureClass.RATE_LIMITED) {
          backoffForRateLimit(attempt);
        }
        log.warn("图形题批改调用失败，准备重试: questionIndex={}, failureClass={}, "
                + "attempt={}/{}",
            question.questionIndex(), failureClass, attempt, MAX_VISUAL_GRADE_ATTEMPTS);
      }
    }
    throw new BusinessException(
        ErrorCode.INTERVIEW_EVALUATION_FAILED, "图形主观题批改失败，请稍后重试");
  }

  private void backoffForRateLimit(int attempt) {
    try {
      VisionRetryPolicy.rateLimitBackoff(attempt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.INTERVIEW_EVALUATION_FAILED, "图形主观题批改等待被中断，请稍后重试", e);
    }
  }

  private LoadedMedia loadMedia(Long paperId, InterviewQuestionDTO question,
                                List<String> assetIds) {
    List<Media> media = new ArrayList<>();
    StringBuilder guide = new StringBuilder();
    LinkedHashSet<String> unique = new LinkedHashSet<>(assetIds == null ? List.of() : assetIds);
    for (String assetId : unique) {
      if (assetId == null || assetId.isBlank() || media.size() >= MAX_IMAGES) {
        continue;
      }
      assetService.find(paperId, assetId).ifPresent(asset -> {
        media.add(toMedia(asset));
        guide.append("图片 ").append(media.size())
            .append("：asset=").append(asset.getAssetKey())
            .append("，kind=").append(nullToEmpty(asset.getKind()))
            .append("，role=").append(nullToEmpty(asset.getRole()))
            .append('\n');
      });
    }
    Integer pageNo = question.sourcePageFrom();
    if (pageNo != null && media.size() < MAX_IMAGES) {
      pageRepository.findByPaperIdAndPageNo(paperId, pageNo)
          .map(PaperPageEntity::getImageKey)
          .filter(key -> key != null && !key.isBlank())
          .ifPresent(key -> {
            media.add(new Media(MimeTypeUtils.IMAGE_JPEG,
                new ByteArrayResource(storageService.downloadFile(key),
                    "question-" + question.questionIndex() + "-full-page-" + pageNo + ".jpg")));
            guide.append("图片 ").append(media.size())
                .append("：整页原图，用于核对裁图上下文，page=").append(pageNo).append('\n');
          });
    }
    if (!media.isEmpty()) {
      return new LoadedMedia(List.copyOf(media), guide.toString());
    }
    if (pageNo == null) {
      return new LoadedMedia(List.of(), "");
    }
    return pageRepository.findByPaperIdAndPageNo(paperId, pageNo)
        .map(PaperPageEntity::getImageKey)
        .filter(key -> key != null && !key.isBlank())
        .map(key -> new LoadedMedia(
            List.of(new Media(MimeTypeUtils.IMAGE_JPEG,
                new ByteArrayResource(storageService.downloadFile(key),
                    "question-" + question.questionIndex() + "-page-" + pageNo + ".jpg"))),
            "图片 1：整页原图，page=" + pageNo + "\n"))
        .orElse(new LoadedMedia(List.of(), ""));
  }

  private Media toMedia(PaperAssetEntity asset) {
    if (asset.getStorageKey() == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "题目图片不可用");
    }
    byte[] bytes = assetService.download(asset);
    try {
      bytes = imageNormalizer.prepareForVision(bytes);
    } catch (Exception e) {
      log.warn("图形批改图片预处理失败，使用原图: asset={}, error={}",
          asset.getAssetKey(), e.getMessage());
    }
    return new Media(MimeTypeUtils.IMAGE_JPEG,
        new ByteArrayResource(bytes,
            "asset-" + asset.getAssetKey() + ".jpg"));
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private String limitText(String value, int maxChars) {
    String normalized = value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= maxChars
        ? normalized : normalized.substring(0, maxChars).stripTrailing() + "…";
  }

  private String read(ResourceLoader loader, String path) throws IOException {
    return loader.getResource(path).getContentAsString(StandardCharsets.UTF_8);
  }
}
