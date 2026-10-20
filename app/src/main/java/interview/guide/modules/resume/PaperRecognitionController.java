package interview.guide.modules.resume;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.resume.model.PaperRecognitionDTO;
import interview.guide.modules.resume.model.PaperUploadResult;
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
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<PaperUploadResult> upload(@RequestParam("files") List<MultipartFile> files) {
    return Result.success(recognitionService.upload(files));
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
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<Void> retryPage(@PathVariable Long id, @PathVariable int pageNo,
                                @RequestParam(defaultValue = "false") boolean forceVision) {
    recognitionService.retryPage(id, pageNo, forceVision);
    return Result.success();
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
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<StructurePreviewDTO> previewStructure(@PathVariable Long id) {
    return Result.success(recognitionService.previewStructure(id));
  }

  /**
   * 确认并切题：合并各页文本后投递现有切题任务。
   */
  @PostMapping("/api/papers/{id}/recognition/confirm")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> confirm(@PathVariable Long id,
                              @RequestBody(required = false) ConfirmRecognitionRequest request) {
    recognitionService.confirm(id, request != null && request.ignoreFailed());
    return Result.success();
  }

  @PostMapping("/api/papers/{id}/recognition/retry")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> retryRecognition(@PathVariable Long id,
                                       @RequestBody(required = false) RetryRecognitionRequest request) {
    recognitionService.retryRecognition(id, request != null && request.all());
    return Result.success();
  }
}
