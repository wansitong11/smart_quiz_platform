package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class RagQueryTraceCollectorTest {
  @Test
  @DisplayName("并行工具命中按资料和片段去重，不丢失轨迹")
  void mergesConcurrentToolResults() throws Exception {
    var collector = new RagQueryTraceCollector(1L, "问题", "资料", "模型");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < 100; index++) {
        int item = index;
        executor.submit(() -> {
          collector.addToolCall(new RagQueryTraceCollector.ToolCall("search", 1, 0.9, 1, false));
          collector.addDirectHits(List.of(new Document("doc-" + (item % 10), "正文", Map.of("kb_id", "1"))));
        });
      }
      executor.shutdown();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(collector.toolCalls()).hasSize(100);
    assertThat(collector.directHitCounts()).containsExactlyEntriesOf(Map.of(1L, 10));
    assertThat(collector.retrievedKbIds()).containsExactly(1L);
  }
}
