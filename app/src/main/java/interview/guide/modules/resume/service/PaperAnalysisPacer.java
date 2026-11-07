package interview.guide.modules.resume.service;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aicost.service.AiUsageGate;
import interview.guide.modules.resume.log.PaperFlowLog;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 解析批次的轻量节流器。
 *
 * <p>解析是在答案键批次之后紧接着启动的，二者共享同一个
 * {@code PAPER_EXTRACTION} RPM。如果前一阶段已经把当前分钟窗口用满，继续提交只会被
 * {@link AiUsageGate} 立即拒绝。这里在每批调用前检查窗口；已满时等待窗口过期，
 * 真正的放行仍由 AiUsageGate 完成，不复制闸门计数逻辑。免费文本模型还会在每批前
 * 执行短冷却，降低连续请求触发上游 429 的概率。</p>
 */
@Component
@RequiredArgsConstructor
public class PaperAnalysisPacer {

  private static final long MAX_JITTER_MS = 250;

  private final AiUsageGate usageGate;
  private final PaperAnswerExecutorProperties properties;

  public void awaitAvailable(Long paperId) {
    awaitAvailable(paperId, false);
  }

  public void awaitAvailable(Long paperId, boolean freeTextModel) {
    AiCallContextHolder.AiCallContext context = AiCallContextHolder.get();
    if (context != null && context.userId() != null) {
      waitForLocalRpmWindow(paperId, context);
    }
    waitForFreeModelCooldown(paperId, freeTextModel);
  }

  private void waitForLocalRpmWindow(
      Long paperId, AiCallContextHolder.AiCallContext context) {
    AiFeature feature = context.feature() == null
        ? AiFeature.PAPER_EXTRACTION : context.feature();
    long remaining = usageGate.remainingRpmWindowMillisIfExhausted(
        feature, context.userId());
    if (remaining <= 0) {
      return;
    }

    Duration maxWait = properties.getAnalysisRateLimitMaxWait();
    long maxWaitMs = maxWait == null ? 65_000L : maxWait.toMillis();
    long jitter = ThreadLocalRandom.current().nextLong(MAX_JITTER_MS + 1);
    long waitMs = Math.min(remaining + jitter, Math.max(0, maxWaitMs));
    if (waitMs <= 0) {
      return;
    }

    PaperFlowLog.warn("ANSWER_ANALYSIS_RATE_LIMIT_WAIT paperId={}, feature={}, "
            + "remainingMs={}, waitMs={}",
        paperId, feature, remaining, waitMs);
    try {
      sleep(waitMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
          "题目解析等待额度恢复被中断，请稍后重试", e);
    }
  }

  private void waitForFreeModelCooldown(Long paperId, boolean freeTextModel) {
    if (!freeTextModel) {
      return;
    }
    Duration cooldown = properties.getAnalysisFreeModelCooldown();
    long cooldownMs = cooldown == null ? 0 : Math.max(0, cooldown.toMillis());
    if (cooldownMs <= 0) {
      return;
    }
    PaperFlowLog.event("ANSWER_ANALYSIS_FREE_MODEL_COOLDOWN paperId={}, waitMs={}",
        paperId, cooldownMs);
    try {
      sleep(cooldownMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED,
          "题目解析等待免费模型冷却被中断，请稍后重试", e);
    }
  }

  protected void sleep(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }
}
