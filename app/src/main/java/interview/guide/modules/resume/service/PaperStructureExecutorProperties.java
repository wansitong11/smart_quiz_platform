package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutorProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 结构切题分块并行配置。
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.paper.structure")
public class PaperStructureExecutorProperties extends BoundedExecutorProperties {
}
