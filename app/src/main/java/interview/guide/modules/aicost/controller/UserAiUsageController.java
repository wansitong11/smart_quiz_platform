package interview.guide.modules.aicost.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.aicost.dto.AiUsageDtos.EstimateRequest;
import interview.guide.modules.aicost.dto.AiUsageDtos.EstimateResult;
import interview.guide.modules.aicost.dto.AiUsageDtos.FeatureUsage;
import interview.guide.modules.aicost.dto.AiUsageDtos.QuotaView;
import interview.guide.modules.aicost.service.AiUsageEstimateService;
import interview.guide.modules.auth.security.CurrentUserHolder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-usage")
@RequiredArgsConstructor
public class UserAiUsageController {

  private final AiUsageEstimateService estimateService;

  @GetMapping("/summary")
  public Result<List<FeatureUsage>> summary() {
    return Result.success(estimateService.summary(CurrentUserHolder.currentUserId()));
  }

  @GetMapping("/quota")
  public Result<QuotaView> quota() {
    return Result.success(estimateService.quota(CurrentUserHolder.currentUserId()));
  }

  @GetMapping("/estimate")
  public Result<EstimateResult> estimate(
      @RequestParam(defaultValue = "PAPER_IMPORT") String feature,
      @RequestParam(required = false) Long pageCount,
      @RequestParam(required = false) Long questionCount,
      @RequestParam(required = false) Long contextTokens,
      @RequestParam(required = false) Long chunkCount,
      @RequestParam(required = false) Long outputTokens) {
    EstimateRequest request = new EstimateRequest(
        feature, pageCount, questionCount, contextTokens, chunkCount, outputTokens);
    return Result.success(estimateService.estimate(CurrentUserHolder.currentUserId(), request));
  }
}
