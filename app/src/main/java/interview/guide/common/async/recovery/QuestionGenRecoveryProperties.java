package interview.guide.common.async.recovery;

import interview.guide.common.async.AsyncTaskRecoveryProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 面试会话出题卡住任务恢复配置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Component
@Validated
@ConfigurationProperties(prefix = "app.async.recovery.question-gen")
public class QuestionGenRecoveryProperties extends AsyncTaskRecoveryProperties {
}
