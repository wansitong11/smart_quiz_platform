package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 结构切题分块并行执行器。
 */
@Configuration
public class PaperStructureConfiguration {

  @Bean(name = "paperStructureExecutor", destroyMethod = "shutdown")
  public BoundedExecutor paperStructureExecutor(
      PaperStructureExecutorProperties properties,
      BoundedExecutorFactory factory) {
    return factory.create("paper-structure", properties);
  }
}
