package interview.guide.modules.resume.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import interview.guide.common.async.StreamWorkerRegistry;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.PaperInputProcessingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("试卷输入阶段预算结算")
class PaperInputStreamConsumerBudgetTest {

  @Test
  @DisplayName("输入解析成功后不提前结算上传预算")
  void shouldDelegateBudgetFinalizationToDownstreamStage() {
    PaperInputStreamConsumer consumer = new PaperInputStreamConsumer(
        mock(RedisService.class),
        mock(StreamWorkerRegistry.class),
        mock(ResumeRepository.class),
        mock(PaperInputProcessingService.class)
    );

    assertThat(consumer.shouldFinalizeBudgetOnSuccess()).isFalse();
  }
}
