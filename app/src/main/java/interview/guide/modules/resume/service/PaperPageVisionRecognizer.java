package interview.guide.modules.resume.service;

import interview.guide.common.ai.JsonFailureFeedback;
import interview.guide.common.ai.JsonFailureFeedbackFormatter;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.VisionFailureClass;
import interview.guide.common.ai.VisionFailureClassifier;
import interview.guide.common.ai.VisionRetryPolicy;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.PageGeometryDTO;
import interview.guide.modules.resume.model.PageOcrDTO;
import interview.guide.modules.resume.model.PageRecognition;
import interview.guide.modules.resume.model.PageRecognitionOutput;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.log.PaperFlowLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 单页视觉识别调用器。
 *
 * <p>只负责模型调用、失败重试预算和结果组装，不负责存储、fencing 或资源落库。</p>
 */
@Slf4j
@Component
public class PaperPageVisionRecognizer {

  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PaperRecognitionExecutorProperties recognitionProperties;
  private final PageRecognitionValidator recognitionValidator;
  private final ObjectMapper objectMapper;
  private final BeanOutputConverter<PageRecognitionOutput> outputConverter;
  private final BeanOutputConverter<PageOcrDTO> ocrOutputConverter;
  private final BeanOutputConverter<PageGeometryDTO> geometryOutputConverter;
  private final String systemPrompt;
  private final String ocrSystemPrompt;
  private final String geometrySystemPrompt;

  public PaperPageVisionRecognizer(
      StructuredOutputInvoker structuredOutputInvoker,
      PaperRecognitionExecutorProperties recognitionProperties,
      PageRecognitionValidator recognitionValidator,
      ObjectMapper objectMapper,
      ResourceLoader resourceLoader
  ) throws IOException {
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.recognitionProperties = recognitionProperties;
    this.recognitionValidator = recognitionValidator;
    this.objectMapper = objectMapper;
    this.outputConverter = new BeanOutputConverter<>(PageRecognitionOutput.class);
    this.ocrOutputConverter = new BeanOutputConverter<>(PageOcrDTO.class);
    this.geometryOutputConverter = new BeanOutputConverter<>(PageGeometryDTO.class);
    this.systemPrompt = loadPrompt(resourceLoader,
        "prompts/paper-page-recognition-system.st", outputConverter);
    this.ocrSystemPrompt = loadPrompt(resourceLoader,
        "prompts/paper-page-ocr-system.st", ocrOutputConverter);
    this.geometrySystemPrompt = loadPrompt(resourceLoader,
        "prompts/paper-page-geometry-system.st", geometryOutputConverter);
  }

  /**
   * 识别一页并返回可交给上层持久化的结构化结果。
   */
  public PageRecognition recognize(
      PaperPageEntity page,
      Integer pageCount,
      byte[] jpeg,
      ChatClient visionClient
  ) {
    Media media = new Media(MimeTypeUtils.IMAGE_JPEG,
        new ByteArrayResource(jpeg, "page-" + page.getPageNo() + ".jpg"));
    long startNanos = System.nanoTime();
    PaperFlowLog.event("VISION_RECOGNIZE_BEGIN mode={}, imageBytes={}, maxCalls={}, pageCount={}",
        recognitionProperties.isTwoStageRecognitionEnabled() ? "TWO_STAGE" : "ONE_STAGE",
        jpeg == null ? 0 : jpeg.length,
        recognitionProperties.getMaxVisionCallsPerPage(), pageCount);
    try {
      PageRecognition result = recognitionProperties.isTwoStageRecognitionEnabled()
          ? recognizeInTwoStages(page, pageCount, jpeg, media, visionClient)
          : recognizeInOneStage(page, pageCount, jpeg, media, visionClient);
      PaperFlowLog.event("VISION_RECOGNIZE_END status=SUCCESS, durationMs={}",
          elapsedMs(startNanos));
      return result;
    } catch (RuntimeException e) {
      PaperFlowLog.error("VISION_RECOGNIZE_END status=FAILED, durationMs={}",
          e, elapsedMs(startNanos));
      throw e;
    }
  }

  private PageRecognition recognizeInOneStage(
      PaperPageEntity page,
      Integer pageCount,
      byte[] jpeg,
      Media media,
      ChatClient visionClient
  ) {
    int maxCalls = Math.max(1, recognitionProperties.getMaxVisionCallsPerPage());
    int invalidOutputRetryThreshold = Math.max(2,
        recognitionProperties.getInvalidOutputRetryThreshold());
    VisionFailureClass lastFailure = null;
    JsonFailureFeedback lastFeedback = null;
    int invalidStreak = 0;
    int truncatedRetries = 0;
    int transportRetries = 0;
    PageRecognitionOutput output = null;
    PageRecognition result = null;
    for (int attempt = 1; attempt <= maxCalls; attempt++) {
      int currentAttempt = attempt;
      String userPrompt = buildOneStagePrompt(page, pageCount, lastFailure, lastFeedback);
      PaperFlowLog.event("VISION_CALL_BEGIN mode=ONE_STAGE, attempt={}/{}, imageBytes={}, "
              + "promptChars={}",
          attempt, maxCalls, jpeg.length, userPrompt.length());
      try {
        output = structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
            visionClient,
            systemPrompt,
            userPrompt,
            List.of(media),
            outputConverter,
            ErrorCode.PAPER_RECOGNITION_FAILED,
            "第 " + page.getPageNo() + " 页识别失败：",
            "试卷识别-第" + page.getPageNo() + "页",
            log,
            content -> PaperFlowLog.raw(
                "VISION_MODEL_RESPONSE mode=ONE_STAGE attempt=" + currentAttempt, content)
        );
        result = output.toPageRecognition();
      } catch (BusinessException e) {
        lastFailure = VisionFailureClassifier.classify(e);
        PaperFlowLog.error("VISION_CALL_FAILED mode=ONE_STAGE, attempt={}/{}, failureClass={}",
            e, attempt, maxCalls, lastFailure);
        if (lastFailure == VisionFailureClass.INVALID_OUTPUT) {
          invalidStreak++;
          lastFeedback = JsonFailureFeedbackFormatter.extract(e);
          boolean fallbackEligible = recognitionProperties.isInvalidOutputFallbackEnabled()
              && !recognitionProperties.isTwoStageRecognitionEnabled();
          if (fallbackEligible
              && (invalidStreak >= invalidOutputRetryThreshold || attempt >= maxCalls)) {
            PaperFlowLog.event("VISION_FALLBACK_BEGIN mode=ONE_STAGE, invalidStreak={}, "
                    + "attempt={}/{}",
                invalidStreak, attempt, maxCalls);
            return recognizeInvalidOutputFallback(page, pageCount, jpeg, media, visionClient);
          }
          if (attempt >= maxCalls) {
            throw e;
          }
          log.warn("页面结构错误，准备带原因重试: paperId={}, pageNo={}, invalidStreak={}, "
                  + "attempt={}/{}",
              page.getPaperId(), page.getPageNo(), invalidStreak, attempt, maxCalls);
          continue;
        }
        if (lastFailure == VisionFailureClass.OUTPUT_TRUNCATED) {
          invalidStreak = 0;
          lastFeedback = null;
          truncatedRetries++;
          boolean retryBudgetExhausted = attempt >= maxCalls
              || truncatedRetries > Math.max(
                  0, recognitionProperties.getOutputTruncatedRetryMaxCount());
          if (retryBudgetExhausted) {
            boolean fallbackEligible = recognitionProperties.isInvalidOutputFallbackEnabled()
                && !recognitionProperties.isTwoStageRecognitionEnabled();
            if (fallbackEligible) {
              PaperFlowLog.event("VISION_FALLBACK_BEGIN mode=ONE_STAGE, reason=OUTPUT_TRUNCATED, "
                      + "truncatedRetries={}, attempt={}/{}",
                  truncatedRetries, attempt, maxCalls);
              return recognizeInvalidOutputFallback(page, pageCount, jpeg, media, visionClient);
            }
            throw e;
          }
          log.warn("页面输出被截断，准备减少重复输出后定向重试: paperId={}, pageNo={}, "
                  + "truncatedRetries={}, attempt={}/{}",
              page.getPaperId(), page.getPageNo(), truncatedRetries, attempt, maxCalls);
          continue;
        }
        invalidStreak = 0;
        lastFeedback = null;
        if (lastFailure == VisionFailureClass.TRANSIENT_TIMEOUT) {
          if (recognitionProperties.isTransportRetryEnabled()
              && transportRetries < Math.max(1,
                  recognitionProperties.getTransportRetryMaxCount())
              && attempt < maxCalls) {
            transportRetries++;
            backoffForTransportRetry();
            log.warn("页面传输失败，准备退避重试: paperId={}, pageNo={}, attempt={}/{}",
                page.getPaperId(), page.getPageNo(), attempt, maxCalls);
            continue;
          }
          throw e;
        }
        if (!VisionRetryPolicy.shouldRetry(lastFailure, attempt, maxCalls)) {
          throw e;
        }
        log.warn("页面模型调用失败，准备重试: paperId={}, pageNo={}, failureClass={}, "
                + "attempt={}/{}",
            page.getPaperId(), page.getPageNo(), lastFailure, attempt, maxCalls);
        if (lastFailure == VisionFailureClass.RATE_LIMITED) {
          backoffForRateLimit(attempt);
        }
        continue;
      }
      invalidStreak = 0;
      lastFeedback = null;
      PaperFlowLog.json(objectMapper,
          "VISION_PARSED_RESULT mode=ONE_STAGE attempt=" + attempt, result);
      String text = pageText(result);
      boolean hasAssets = result.assets() != null && !result.assets().isEmpty();
      boolean hasVisualContent = result.hasFigure() || hasAssets;
      boolean missingLayout = hasVisualContent && !hasUsableLayout(result.lines());
      boolean incomplete = recognitionValidator.isLikelyIncomplete(jpeg, text, hasVisualContent);
      boolean missingMarkup = recognitionValidator.hasMissingRequiredMarkup(text);
      boolean missingAssets = recognitionValidator.hasMissingVisualAssets(
          text, result.hasFigure(), hasAssets);
      boolean hasOptionRow = hasAssetKind(result.assets(), "OPTION_ROW");
      boolean hasStemFigureAsset = hasAssetKind(result.assets(), "FIGURE", "STEM");
      boolean missingStemFigure = recognitionValidator.hasMissingDeclaredStemFigureAssets(
          result.stemFigureAnchors(), result.assets())
          || hasOptionRow && !hasStemFigureAsset
              && recognitionValidator.referencesStemFigure(text);
      boolean missingLines = !hasVisualContent && text.isEmpty()
          && recognitionValidator.hasVisibleText(jpeg);
      PaperFlowLog.event("VISION_RESULT_VALIDATION attempt={}/{}, chars={}, lines={}, assets={}, "
              + "hasFigure={}, hasVisualContent={}, incomplete={}, missingMarkup={}, "
              + "missingLayout={}, missingAssets={}, missingStemFigure={}, optionRow={}, "
              + "stemFigureAsset={}, missingLines={}",
          attempt, maxCalls, text.length(), result.lines() == null ? 0 : result.lines().size(),
          result.assets() == null ? 0 : result.assets().size(), result.hasFigure(),
          hasVisualContent, incomplete, missingMarkup, missingLayout, missingAssets,
          missingStemFigure, hasOptionRow, hasStemFigureAsset, missingLines);
      if (missingMarkup) {
        log.info("页面存在加点/着重号但未输出标记，交由人工校对: paperId={}, pageNo={}",
            page.getPaperId(), page.getPageNo());
      }
      if (!incomplete && !missingLayout && !missingAssets
          && !missingStemFigure && !missingLines) {
        return result;
      }
      lastFailure = VisionFailureClass.INCOMPLETE_CONTENT;
      lastFeedback = null;
      log.warn("页面识别结果需重试: paperId={}, pageNo={}, chars={}, hasFigure={}, "
              + "incomplete={}, missingMarkup={}, missingLayout={}, missingAssets={}, "
              + "missingStemFigure={}, missingLines={}, failureClass={}, attempt={}/{}",
          page.getPaperId(), page.getPageNo(), text.length(), result.hasFigure(),
          incomplete, missingMarkup, missingLayout, missingAssets, missingStemFigure, missingLines,
          VisionFailureClass.INCOMPLETE_CONTENT, attempt, maxCalls);
    }
    if (result != null) {
      PaperFlowLog.warn("VISION_RESULT_RETURNED_AFTER_BUDGET maxCalls={} result={}",
          maxCalls, result);
    }
    return result;
  }

  /**
   * OCR 与资源几何分开调用。OCR 不输出坐标，几何阶段只处理视觉资源。
   */
  private PageRecognition recognizeInTwoStages(
      PaperPageEntity page,
      Integer pageCount,
      byte[] jpeg,
      Media media,
      ChatClient visionClient
  ) {
    int callBudget = Math.max(1, recognitionProperties.getMaxVisionCallsPerPage());
    return recognizeByOcrThenGeometry(
        page, pageCount, jpeg, media, visionClient, callBudget, "TWO_STAGE");
  }

  /**
   * 结构错误连续达到阈值后的兜底：先拿文字，再按剩余预算定位图形。
   */
  private PageRecognition recognizeInvalidOutputFallback(
      PaperPageEntity page,
      Integer pageCount,
      byte[] jpeg,
      Media media,
      ChatClient visionClient
  ) {
    int fallbackBudget = Math.max(1, recognitionProperties.getInvalidOutputFallbackMaxCalls());
    PaperFlowLog.event("VISION_FALLBACK_RUN pageNo={}, maxCalls={}",
        page.getPageNo(), fallbackBudget);
    PageRecognition result = recognizeByOcrThenGeometry(
        page, pageCount, jpeg, media, visionClient, fallbackBudget, "INVALID_OUTPUT_FALLBACK");
    PaperFlowLog.json(objectMapper, "VISION_FALLBACK_FINAL_RESULT", result);
    return result;
  }

  private PageRecognition recognizeByOcrThenGeometry(
      PaperPageEntity page,
      Integer pageCount,
      byte[] jpeg,
      Media media,
      ChatClient visionClient,
      int callBudget,
      String mode
  ) {
    int ocrBudget = callBudget > 1 ? callBudget - 1 : 1;
    PageOcrAttempt ocrAttempt = requestPageOcr(
        page, pageCount, jpeg, media, visionClient, ocrBudget);
    PageOcrDTO ocr = ocrAttempt.result();
    PaperFlowLog.json(objectMapper, mode + "_OCR_RESULT calls=" + ocrAttempt.calls(), ocr);
    String text = pageText(ocr);
    boolean hasVisualContent = ocr.hasFigure()
        || recognitionValidator.referencesVisualContent(text)
        || recognitionValidator.referencesStemFigure(text);
    PaperFlowLog.event(mode + "_VISUAL_CHECK hasFigure={}, referencesVisualContent={}, "
            + "referencesStemFigure={}, hasVisualContent={}, calls={}",
        ocr.hasFigure(), recognitionValidator.referencesVisualContent(text),
        recognitionValidator.referencesStemFigure(text), hasVisualContent, ocrAttempt.calls());

    if (!hasVisualContent) {
      PageRecognition result = new PageRecognition(
          page.getPageNo(), text, List.of(), List.of(), false,
          ocr.stemFigureAnchors(), ocr.notes());
      PaperFlowLog.json(objectMapper, mode + "_FINAL_RESULT", result);
      return result;
    }

    int remainingCalls = callBudget - ocrAttempt.calls();
    if (remainingCalls < 1) {
      throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
          "第 " + page.getPageNo() + " 页 OCR 后没有足够调用预算定位图片，请重试该页");
    }
    PageGeometryDTO geometry = requestPageGeometry(
        page, media, visionClient, text, remainingCalls);
    PageRecognition result = new PageRecognition(
        page.getPageNo(),
        text,
        geometry.lines(),
        geometry.assets(),
        ocr.hasFigure() || geometry.assets() != null && !geometry.assets().isEmpty(),
        mergeAnchors(ocr.stemFigureAnchors(), geometry.stemFigureAnchors()),
        mergeNotes(ocr.notes(), geometry.notes())
    );
    PaperFlowLog.json(objectMapper, mode + "_FINAL_RESULT", result);
    return result;
  }

  private PageOcrAttempt requestPageOcr(
      PaperPageEntity page,
      Integer pageCount,
      byte[] jpeg,
      Media media,
      ChatClient visionClient,
      int maxCalls
  ) {
    VisionFailureClass lastFailure = null;
    JsonFailureFeedback lastFeedback = null;
    int transportRetries = 0;
    for (int attempt = 1; attempt <= maxCalls; attempt++) {
      int currentAttempt = attempt;
      String userPrompt = "这是第 " + page.getPageNo()
          + " 页（共 " + (pageCount != null ? pageCount : 1) + " 页）。"
          + "请只转写这一页的全部印刷体内容，不要定位图片或输出坐标。"
          + retryInstruction(lastFailure, lastFeedback);
      PaperFlowLog.event("VISION_CALL_BEGIN mode=OCR, attempt={}/{}, imageBytes={}, promptChars={}",
          attempt, maxCalls, jpeg.length, userPrompt.length());
      try {
        PageOcrDTO result = structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
            visionClient,
            ocrSystemPrompt,
            userPrompt,
            List.of(media),
            ocrOutputConverter,
            ErrorCode.PAPER_RECOGNITION_FAILED,
            "第 " + page.getPageNo() + " 页 OCR 失败：",
            "试卷 OCR-第" + page.getPageNo() + "页",
            log,
            content -> PaperFlowLog.raw(
                "VISION_MODEL_RESPONSE mode=OCR attempt=" + currentAttempt, content)
        );
        PaperFlowLog.json(objectMapper,
            "VISION_PARSED_RESULT mode=OCR attempt=" + attempt, result);
        String text = pageText(result);
        if (!recognitionValidator.isLikelyIncomplete(jpeg, text, result.hasFigure())) {
          return new PageOcrAttempt(result, attempt);
        }
        lastFailure = VisionFailureClass.INCOMPLETE_CONTENT;
        lastFeedback = null;
        log.warn("OCR 结果不完整，准备重试: paperId={}, pageNo={}, chars={}, "
                + "attempt={}/{}",
            page.getPaperId(), page.getPageNo(), text.length(), attempt, maxCalls);
      } catch (BusinessException e) {
        lastFailure = VisionFailureClassifier.classify(e);
        PaperFlowLog.error("VISION_CALL_FAILED mode=OCR, attempt={}/{}, failureClass={}",
            e, attempt, maxCalls, lastFailure);
        if (lastFailure == VisionFailureClass.INVALID_OUTPUT) {
          lastFeedback = JsonFailureFeedbackFormatter.extract(e);
        } else {
          lastFeedback = null;
          if (lastFailure == VisionFailureClass.TRANSIENT_TIMEOUT) {
            if (recognitionProperties.isTransportRetryEnabled()
                && transportRetries < Math.max(1,
                    recognitionProperties.getTransportRetryMaxCount())
                && attempt < maxCalls) {
              transportRetries++;
              backoffForTransportRetry();
              log.warn("OCR 传输失败，准备退避重试: paperId={}, pageNo={}, attempt={}/{}",
                  page.getPaperId(), page.getPageNo(), attempt, maxCalls);
              continue;
            }
            throw e;
          }
        }
        if (!VisionRetryPolicy.shouldRetry(lastFailure, attempt, maxCalls)) {
          throw e;
        }
        if (lastFailure == VisionFailureClass.RATE_LIMITED) {
          backoffForRateLimit(attempt);
        }
        log.warn("OCR 调用失败，准备重试: paperId={}, pageNo={}, failureClass={}, "
                + "attempt={}/{}",
            page.getPaperId(), page.getPageNo(), lastFailure, attempt, maxCalls);
      }
    }
    throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
        "第 " + page.getPageNo() + " 页 OCR 连续 " + maxCalls
            + " 次未返回完整内容，请重试该页");
  }

  private PageGeometryDTO requestPageGeometry(
      PaperPageEntity page,
      Media media,
      ChatClient visionClient,
      String ocrText,
      int maxCalls
  ) {
    VisionFailureClass lastFailure = null;
    JsonFailureFeedback lastFeedback = null;
    int transportRetries = 0;
    for (int attempt = 1; attempt <= maxCalls; attempt++) {
      int currentAttempt = attempt;
      String userPrompt = "第 " + page.getPageNo() + " 页 OCR 文本如下。"
          + "请只定位本页视觉资源，输出必要的文本行、资源 bbox 和锚点；"
          + "不要重新转写整页。\n\n<<<OCR\n" + ocrText + "\nOCR>>>"
          + retryInstruction(lastFailure, lastFeedback);
      PaperFlowLog.event("VISION_CALL_BEGIN mode=GEOMETRY, attempt={}/{}, mediaCount=1, "
              + "ocrChars={}, promptChars={}",
          attempt, maxCalls, ocrText.length(), userPrompt.length());
      try {
        PageGeometryDTO result =
            structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
                visionClient,
                geometrySystemPrompt,
                userPrompt,
                List.of(media),
                geometryOutputConverter,
                ErrorCode.PAPER_RECOGNITION_FAILED,
                "第 " + page.getPageNo() + " 页资源定位失败：",
                "试卷资源定位-第" + page.getPageNo() + "页",
                log,
                content -> PaperFlowLog.raw(
                    "VISION_MODEL_RESPONSE mode=GEOMETRY attempt=" + currentAttempt, content)
            );
        PaperFlowLog.json(objectMapper,
            "VISION_PARSED_RESULT mode=GEOMETRY attempt=" + attempt, result);
        boolean hasAssets = result.assets() != null && !result.assets().isEmpty();
        boolean missingLayout = !hasUsableLayout(result.lines());
        boolean missingAnchors = recognitionValidator.hasMissingDeclaredStemFigureAssets(
            result.stemFigureAnchors(), result.assets());
        if (!missingLayout && hasAssets && !missingAnchors) {
          return result;
        }
        lastFailure = VisionFailureClass.INCOMPLETE_CONTENT;
        lastFeedback = null;
        log.warn("资源定位结果不完整，准备重试: paperId={}, pageNo={}, "
                + "missingLayout={}, missingAssets={}, missingAnchors={}, attempt={}/{}",
            page.getPaperId(), page.getPageNo(), missingLayout, !hasAssets, missingAnchors,
            attempt, maxCalls);
      } catch (BusinessException e) {
        lastFailure = VisionFailureClassifier.classify(e);
        PaperFlowLog.error("VISION_CALL_FAILED mode=GEOMETRY, attempt={}/{}, failureClass={}",
            e, attempt, maxCalls, lastFailure);
        if (lastFailure == VisionFailureClass.INVALID_OUTPUT) {
          lastFeedback = JsonFailureFeedbackFormatter.extract(e);
        } else {
          lastFeedback = null;
          if (lastFailure == VisionFailureClass.TRANSIENT_TIMEOUT) {
            if (recognitionProperties.isTransportRetryEnabled()
                && transportRetries < Math.max(1,
                    recognitionProperties.getTransportRetryMaxCount())
                && attempt < maxCalls) {
              transportRetries++;
              backoffForTransportRetry();
              log.warn("资源定位传输失败，准备退避重试: paperId={}, pageNo={}, attempt={}/{}",
                  page.getPaperId(), page.getPageNo(), attempt, maxCalls);
              continue;
            }
            throw e;
          }
        }
        if (!VisionRetryPolicy.shouldRetry(lastFailure, attempt, maxCalls)) {
          throw e;
        }
        if (lastFailure == VisionFailureClass.RATE_LIMITED) {
          backoffForRateLimit(attempt);
        }
        log.warn("资源定位调用失败，准备重试: paperId={}, pageNo={}, failureClass={}, "
                + "attempt={}/{}",
            page.getPaperId(), page.getPageNo(), lastFailure, attempt, maxCalls);
      }
    }
    throw new BusinessException(ErrorCode.PAPER_RECOGNITION_FAILED,
        "第 " + page.getPageNo() + " 页资源定位连续 " + maxCalls
            + " 次未返回完整结果，请重试该页");
  }

  private List<String> mergeAnchors(List<String> first, List<String> second) {
    List<String> merged = new ArrayList<>();
    if (first != null) {
      merged.addAll(first);
    }
    if (second != null) {
      merged.addAll(second);
    }
    return merged.stream()
        .filter(anchor -> anchor != null && !anchor.isBlank())
        .distinct()
        .toList();
  }

  private long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private String mergeNotes(String first, String second) {
    String merged = first;
    if (second != null && !second.isBlank()) {
      merged = merged == null || merged.isBlank()
          ? second : merged + "；" + second;
    }
    return merged;
  }

  private String buildOneStagePrompt(
      PaperPageEntity page,
      Integer pageCount,
      VisionFailureClass lastFailure,
      JsonFailureFeedback lastFeedback
  ) {
    return "这是第 " + page.getPageNo()
        + " 页（共 " + (pageCount != null ? pageCount : 1) + " 页）。"
        + "请转写这一页的全部印刷体内容。"
        + retryInstruction(lastFailure, lastFeedback);
  }

  private String retryInstruction(
      VisionFailureClass failureClass,
      JsonFailureFeedback feedback
  ) {
    if (failureClass == null) {
      return "";
    }
    if (failureClass == VisionFailureClass.INVALID_OUTPUT) {
      if (feedback != null) {
        return "\n上一次输出解析失败："
            + JsonFailureFeedbackFormatter.toInstruction(
                feedback, recognitionProperties.getFeedbackMaxChars())
            + " 请严格按格式要求重新返回 JSON。";
      }
      return "\n上一次输出无法解析。请只返回严格 JSON，并确保每个 line 的 text 是字符串、"
          + "bbox 是恰好 4 个数字组成的数组。";
    }
    if (failureClass == VisionFailureClass.OUTPUT_TRUNCATED) {
      return "\n上一次输出在 JSON 完整闭合前被截断。请减少非必要内容，只返回 lines 和必要字段，"
          + "不要重复整页 text，不要输出解释，并确保 JSON 完整结束。";
    }
    if (failureClass == VisionFailureClass.INCOMPLETE_CONTENT) {
      return "\n上一次只返回了部分内容。请重新阅读整页，按从上到下顺序完整转写所有印刷体文字，"
          + "不得只返回第一行或省略中间题目。";
    }
    return "";
  }

  private void backoffForTransportRetry() {
    long baseMs = Math.max(0, recognitionProperties.getTransportRetryBackoffBase().toMillis());
    long jitterMs = ThreadLocalRandom.current().nextLong(0, 501);
    try {
      Thread.sleep(baseMs + jitterMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "试卷页识别等待被中断，请稍后重试", e);
    }
  }

  private void backoffForRateLimit(int attempt) {
    try {
      VisionRetryPolicy.rateLimitBackoff(attempt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "试卷页识别等待被中断，请稍后重试", e);
    }
  }

  private String pageText(PageOcrDTO result) {
    return result.text() == null ? "" : result.text().strip();
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

  private boolean hasAssetKind(List<PageRecognition.PageAsset> assets, String kind) {
    return assets != null && assets.stream()
        .anyMatch(asset -> asset != null && kind.equalsIgnoreCase(asset.kind()));
  }

  private boolean hasAssetKind(
      List<PageRecognition.PageAsset> assets,
      String kind,
      String role
  ) {
    return assets != null && assets.stream()
        .anyMatch(asset -> asset != null
            && kind.equalsIgnoreCase(asset.kind())
            && role.equalsIgnoreCase(asset.role()));
  }

  private String loadPrompt(
      ResourceLoader resourceLoader,
      String path,
      BeanOutputConverter<?> converter
  ) throws IOException {
    return resourceLoader.getResource("classpath:" + path)
        .getContentAsString(StandardCharsets.UTF_8)
        + "\n\n" + converter.getFormat();
  }

  private record PageOcrAttempt(PageOcrDTO result, int calls) {
  }
}
