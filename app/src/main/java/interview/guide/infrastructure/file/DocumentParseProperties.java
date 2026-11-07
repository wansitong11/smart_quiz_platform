package interview.guide.infrastructure.file;

import interview.guide.common.async.BoundedExecutorProperties;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 文档解析的超时与有界线程池配置。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Component
@Validated
@ConfigurationProperties(prefix = "app.document-parse")
public class DocumentParseProperties extends BoundedExecutorProperties {

  @NotNull
  private Duration timeout = Duration.ofMinutes(2);
}
