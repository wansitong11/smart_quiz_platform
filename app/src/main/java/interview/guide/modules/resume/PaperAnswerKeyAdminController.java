package interview.guide.modules.resume;

import interview.guide.common.result.Result;
import interview.guide.modules.resume.service.PaperAnswerKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部答案键维护入口，仅管理员可访问。
 */
@RestController
@RequestMapping("/api/admin/papers")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class PaperAnswerKeyAdminController {

  private final PaperAnswerKeyService answerKeyService;

  @PutMapping("/{paperId}/answer-keys/{questionIndex}")
  public Result<PaperAnswerKeyService.UpdateResult> update(
      @PathVariable Long paperId,
      @PathVariable int questionIndex,
      @RequestBody PaperAnswerKeyService.UpdateAnswerKeyRequest request
  ) {
    return Result.success(answerKeyService.update(paperId, questionIndex, request));
  }
}
