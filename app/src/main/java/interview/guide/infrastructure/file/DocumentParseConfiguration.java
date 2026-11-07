package interview.guide.infrastructure.file;

import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 文档解析专用有界执行器。 */
@Configuration
public class DocumentParseConfiguration {

  @Bean(name = "documentParseExecutor", destroyMethod = "shutdown")
  public BoundedExecutor documentParseExecutor(
      DocumentParseProperties properties,
      BoundedExecutorFactory factory) {
    return factory.create("document-parse", properties);
  }
}
