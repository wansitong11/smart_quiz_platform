package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutorProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 单卷内页级并行识别配置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Component
@Validated
@ConfigurationProperties(prefix = "app.paper.recognition")
public class PaperRecognitionExecutorProperties extends BoundedExecutorProperties {

  public PaperRecognitionExecutorProperties() {
    setPoolSize(3);
  }

  /** 页 worker 等待结果期间保持试卷执行权的心跳周期。 */
  @NotNull
  private Duration heartbeatInterval = Duration.ofSeconds(30);

  /** 超过该时长仍未完成的 PROCESSING 页可在恢复时重新置为 PENDING。 */
  @NotNull
  private Duration pageStaleThreshold = Duration.ofMinutes(10);

  /** 单页允许的视觉 provider 调用总次数。 */
  @Min(1)
  private int maxVisionCallsPerPage = 3;

  /** 同一页因输出截断最多允许的定向重试次数。 */
  @Min(0)
  private int outputTruncatedRetryMaxCount = 1;

  /** 是否启用 OCR 与资源几何两阶段识别；默认保留单次兼容路径。 */
  private boolean twoStageRecognitionEnabled = false;

  /** 单阶段连续结构错误达到该次数后放弃整页重抽，转入两阶段兜底。 */
  @Min(2)
  private int invalidOutputRetryThreshold = 2;

  /** 是否仅对结构错误启用两阶段兜底。 */
  private boolean invalidOutputFallbackEnabled = true;

  /** 结构错误兜底的独立调用预算（OCR + 可选几何）。 */
  @Min(1)
  private int invalidOutputFallbackMaxCalls = 2;

  /** 传输超时是否允许同页有界重试。 */
  private boolean transportRetryEnabled = true;

  /** 传输超时最多重试次数；当前实现按页只允许一次。 */
  @Min(1)
  private int transportRetryMaxCount = 1;

  /** 传输超时重试的基础退避时长，另叠加 0~500ms 抖动。 */
  @NotNull
  private Duration transportRetryBackoffBase = Duration.ofSeconds(1);

  /** 写入下次提示的结构错误反馈最大字符数。 */
  @Min(20)
  private int feedbackMaxChars = 200;
}
