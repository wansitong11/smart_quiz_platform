package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 答案键生成与解析共用的批次执行器。
 */
@Configuration
public class PaperAnswerConfiguration {

  @Bean(name = "paperAnswerExecutor", destroyMethod = "shutdown")
  public BoundedExecutor paperAnswerExecutor(
      PaperAnswerExecutorProperties properties,
      BoundedExecutorFactory factory) {
    return factory.create("paper-answer", properties);
  }
}
