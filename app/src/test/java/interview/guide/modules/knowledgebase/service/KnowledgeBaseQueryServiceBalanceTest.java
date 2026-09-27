package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨库召回的「按资料保底」均衡逻辑（纯内存，不依赖检索服务）。
 */
@DisplayName("跨库召回均衡")
class KnowledgeBaseQueryServiceBalanceTest {

  private final KnowledgeBaseQueryService service = buildService();

  private static KnowledgeBaseQueryService buildService() {
    try {
      return new KnowledgeBaseQueryService(null, null, null, null, null,
          new KnowledgeBaseQueryProperties(), new DefaultResourceLoader(),
          null, null, null, null);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("初始化查询服务失败", e);
    }
  }

  private Document doc(String id, long kbId, double score) {
    return new Document(id, "片段 " + id, Map.of("kb_id", kbId, "score", score));
  }

  @Test
  @DisplayName("只有一份资料时不裁剪：候选 6 条、topK 8 就返回 6 条")
  void keepsSingleKnowledgeBaseUntouched() {
    List<Document> candidates = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      candidates.add(doc("d" + i, 1L, 0.9 - i * 0.01));
    }

    assertThat(service.balanceByKnowledgeBase(candidates, 8)).hasSize(6);
  }

  @Test
  @DisplayName("一份资料霸榜时，其它资料仍能拿到保底名额")
  void reservesQuotaForOtherKnowledgeBases() {
    List<Document> candidates = new ArrayList<>();
    // 资料 1：8 条高分片段（会占满 topK）
    for (int i = 0; i < 8; i++) {
      candidates.add(doc("a" + i, 1L, 0.90 - i * 0.01));
    }
    // 资料 2、3：各 1 条略低分片段
    candidates.add(doc("b0", 2L, 0.80));
    candidates.add(doc("c0", 3L, 0.79));

    List<Document> balanced = service.balanceByKnowledgeBase(candidates, 8);

    assertThat(balanced).hasSize(8);
    // 关键点：低分的另外两份资料也进了上下文，不会被高分资料挤掉
    assertThat(balanced).extracting(Document::getId).contains("b0", "c0");
    // 保底之外的名额仍按分数回给高分资料
    assertThat(balanced).extracting(Document::getId).filteredOn(id -> id.startsWith("a"))
        .hasSizeGreaterThanOrEqualTo(6);
  }

  @Test
  @DisplayName("保底名额受 topK 限制，不会超出")
  void neverExceedsTopK() {
    List<Document> candidates = new ArrayList<>();
    for (long kb = 1; kb <= 5; kb++) {
      for (int i = 0; i < 3; i++) {
        candidates.add(doc("k" + kb + "-" + i, kb, 0.9 - kb * 0.01 - i * 0.001));
      }
    }

    assertThat(service.balanceByKnowledgeBase(candidates, 4)).hasSize(4);
  }

  @Test
  @DisplayName("候选不超过 topK 时原样返回")
  void returnsCandidatesWhenUnderTopK() {
    List<Document> candidates = List.of(doc("a", 1L, 0.9), doc("b", 2L, 0.8));

    assertThat(service.balanceByKnowledgeBase(candidates, 8)).isEqualTo(candidates);
  }
}
