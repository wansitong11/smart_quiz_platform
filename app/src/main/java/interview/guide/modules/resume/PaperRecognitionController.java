package interview.guide.modules.resume;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.ai.AiCost;
import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiCostControl;
import interview.guide.common.ai.AiCostEstimateInput;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.result.Result;
import interview.guide.modules.resume.model.PaperRecognitionDTO;
import interview.guide.modules.resume.model.PaperUploadResult;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.model.StructurePreviewDTO;
import interview.guide.modules.resume.service.PaperRecognitionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 图片 / 扫描件试卷识别接口。
 * <p>
 * 全部按归属校验，越权统一 404（沿用 OwnerScope 约定）。
 * </p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "试卷识别", description = "扫描件 / 拍照试卷的逐页识别与预览校对")
public class PaperRecognitionController {

  private final PaperRecognitionService recognitionService;
  private final AiCostControl aiCostControl;

  public record UpdatePageTextRequest(String text) {
  }

  public record ConfirmRecognitionRequest(boolean ignoreFailed) {
  }

  public record RetryRecognitionRequest(boolean all) {
  }

  /**
   * 上传 1~10 个 PDF / JPG / PNG，按顺序合并为同一份试卷。
   */
  @PostMapping(value = "/api/papers/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @AiCost(feature = AiFeature.PAPER_IMPORT, operation = "upload")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<PaperUploadResult> upload(@RequestParam("files") List<MultipartFile> files) {
    AiCallContext context = AiCallContextHolder.get();
    aiCostControl.reserveOrThrow(
        context == null ? null : context.operationId(),
        context == null ? null : context.userId(),
        AiFeature.PAPER_IMPORT,
        new AiCostEstimateInput((long) Math.max(1, files.size()), null, null, null, null));
    try {
      PaperUploadResult result = recognitionService.upload(files);
      if (result.duplicate() || result.recognitionStatus() == RecognitionStatus.FAILED) {
        finalizeOperation(context);
      }
      return Result.success(result);
    } catch (RuntimeException e) {
      if (context != null) {
        aiCostControl.finalize(context.operationId());
      }
      throw e;
    }
  }

  @GetMapping("/api/papers/{id}/recognition")
  public Result<PaperRecognitionDTO> getRecognition(@PathVariable Long id) {
    return Result.success(recognitionService.getRecognition(id));
  }

  @PutMapping("/api/papers/{id}/pages/{pageNo}")
  public Result<Void> updatePageText(@PathVariable Long id, @PathVariable int pageNo,
                                     @RequestBody UpdatePageTextRequest request) {
    recognitionService.updatePageText(id, pageNo, request == null ? null : request.text());
    return Result.success();
  }

  /**
   * 重试单页；forceVision=true 表示即使有文字层也强制用识图重做。
   */
  @PostMapping("/api/papers/{id}/pages/{pageNo}/retry")
  @AiCost(feature = AiFeature.PAPER_RECOGNIZE, operation = "retryPage")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<Void> retryPage(@PathVariable Long id, @PathVariable int pageNo,
                                @RequestParam(defaultValue = "false") boolean forceVision) {
    AiCallContext context = AiCallContextHolder.get();
    aiCostControl.reserveOrThrow(
        context == null ? null : context.operationId(),
        context == null ? null : context.userId(),
        AiFeature.PAPER_RECOGNIZE,
        new AiCostEstimateInput(1L, null, null, null, null));
    try {
      recognitionService.retryPage(id, pageNo, forceVision);
      return Result.success();
    } catch (RuntimeException e) {
      finalizeOperation(context);
      throw e;
    }
  }

  @DeleteMapping("/api/papers/{id}/pages/{pageNo}")
  public Result<Void> deletePage(@PathVariable Long id, @PathVariable int pageNo) {
    recognitionService.deletePage(id, pageNo);
    return Result.success();
  }

  @GetMapping("/api/papers/{id}/pages/{pageNo}/image")
  public ResponseEntity<byte[]> getPageImage(@PathVariable Long id, @PathVariable int pageNo) {
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .body(recognitionService.getPageImage(id, pageNo));
  }

  /** 图形选项 / 题干图 / 表格截图。 */
  @GetMapping("/api/papers/{id}/assets/{assetKey}")
  public ResponseEntity<byte[]> getAsset(@PathVariable Long id, @PathVariable String assetKey) {
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .body(recognitionService.getAsset(id, assetKey));
  }

  /** 结构预览：确认切题前先看层级与材料挂载。 */
  @PostMapping("/api/papers/{id}/structure/preview")
  @AiCost(feature = AiFeature.PAPER_EXTRACTION, operation = "previewStructure")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<StructurePreviewDTO> previewStructure(@PathVariable Long id) {
    AiCallContext context = AiCallContextHolder.get();
    aiCostControl.reserveOrThrow(
        context == null ? null : context.operationId(),
        context == null ? null : context.userId(),
        AiFeature.PAPER_EXTRACTION,
        new AiCostEstimateInput(1L, null, null, null, null));
    try {
      return Result.success(recognitionService.previewStructure(id));
    } finally {
      finalizeOperation(context);
    }
  }

  /**
   * 确认并切题：合并各页文本后投递现有切题任务。
   */
  @PostMapping("/api/papers/{id}/recognition/confirm")
  @AiCost(feature = AiFeature.PAPER_EXTRACTION, operation = "confirm")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> confirm(@PathVariable Long id,
                              @RequestBody(required = false) ConfirmRecognitionRequest request,
                              @RequestParam(defaultValue = "false") boolean force) {
    AiCallContext context = AiCallContextHolder.get();
    aiCostControl.reserveOrThrow(
        context == null ? null : context.operationId(),
        context == null ? null : context.userId(),
        AiFeature.PAPER_EXTRACTION,
        new AiCostEstimateInput(1L, null, null, null, null));
    try {
      boolean enqueued = recognitionService.confirm(
          id, request != null && request.ignoreFailed(), force);
      if (!enqueued) {
        finalizeOperation(context);
      }
      return Result.success();
    } catch (RuntimeException e) {
      if (context != null) {
        aiCostControl.finalize(context.operationId());
      }
      throw e;
    }
  }

  @PostMapping("/api/papers/{id}/recognition/retry")
  @AiCost(feature = AiFeature.PAPER_RECOGNIZE, operation = "retryRecognition")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> retryRecognition(@PathVariable Long id,
                                       @RequestBody(required = false) RetryRecognitionRequest request) {
    AiCallContext context = AiCallContextHolder.get();
    aiCostControl.reserveOrThrow(
        context == null ? null : context.operationId(),
        context == null ? null : context.userId(),
        AiFeature.PAPER_RECOGNIZE,
        new AiCostEstimateInput(1L, null, null, null, null));
    try {
      recognitionService.retryRecognition(id, request != null && request.all());
      return Result.success();
    } catch (RuntimeException e) {
      finalizeOperation(context);
      throw e;
    }
  }

  private void finalizeOperation(AiCallContext context) {
    if (context != null && context.operationId() != null) {
      aiCostControl.finalize(context.operationId());
    }
  }
}
