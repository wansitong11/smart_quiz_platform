package interview.guide.common.async.recovery;

import interview.guide.common.async.AsyncTaskRecoveryProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 试卷输入处理卡住任务恢复配置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Component
@Validated
@ConfigurationProperties(prefix = "app.async.recovery.paper-input")
public class PaperInputRecoveryProperties extends AsyncTaskRecoveryProperties {
}
