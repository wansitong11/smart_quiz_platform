package interview.guide.modules.interview.service;

import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 面试出题的单会话内部并行执行器；外层会话并发由 Stream worker 池控制。
 */
@Configuration
public class InterviewQuestionExecutorConfiguration {

  @Bean(name = "questionInnerExecutor", destroyMethod = "shutdown")
  public BoundedExecutor questionInnerExecutor(
      InterviewQuestionInnerExecutorProperties properties,
      BoundedExecutorFactory factory) {
    return factory.create("interview-question-inner", properties);
  }
}
