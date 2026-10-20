package interview.guide.common.async.recovery;

import interview.guide.common.async.AsyncTaskRecoveryProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 试卷图片识别卡住任务恢复配置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Component
@Validated
@ConfigurationProperties(prefix = "app.async.recovery.paper-recognition")
public class PaperRecognitionRecoveryProperties extends AsyncTaskRecoveryProperties {
}
