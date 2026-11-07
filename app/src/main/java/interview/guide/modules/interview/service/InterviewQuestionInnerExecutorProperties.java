package interview.guide.modules.interview.service;

import interview.guide.common.async.BoundedExecutorProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 单会话内部出题并行执行器：简历题与方向题并行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Component
@Validated
@ConfigurationProperties(prefix = "app.interview.question-inner.executor")
public class InterviewQuestionInnerExecutorProperties extends BoundedExecutorProperties {
}
