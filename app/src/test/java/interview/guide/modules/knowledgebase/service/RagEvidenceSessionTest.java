package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.knowledgebase.service.RagContextAssembler.EvidenceBlock;
import interview.guide.modules.knowledgebase.service.RagContextAssembler.EvidenceUnit;
import interview.guide.modules.knowledgebase.service.RagContextAssembler.PreparedContext;
import interview.guide.modules.knowledgebase.service.RagContextAssembler.Status;
import interview.guide.modules.knowledgebase.service.scope.ResolvedScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class RagEvidenceSessionTest {
  static RagEvidenceSession session() {
    return new RagEvidenceSession(1L, 1L, false, ResolvedScope.ofSelection(List.of(4L, 23L)),
        new AiCallContext(1L, AiFeature.KB_QA, "test", "request", "operation", "test"), "当前问题");
  }

  static PreparedContext prepared(String id, long kbId, String text) {
    var block = new EvidenceBlock("C1", new Document(id, text, Map.of("kb_id", kbId)), kbId,
        "资料" + kbId, "章节", null, -1, true, 0);
    var unit = new EvidenceUnit("E1", "C1", List.of("C1"));
    String rendered = RagContextAssembler.renderEvidence(List.of(block), List.of(unit));
    return new PreparedContext(Status.READY, List.of(block), List.of(unit), rendered,
        RagContextAssembler.tokenCount(rendered));
  }

  @Test
  @DisplayName("跨工具全局编号不冲突且缓存命中不重复提供原文或消耗批次")
  void cacheAndNumbering() {
    var session = session();
    var first = session.reviewCached("first", 2, 6000, () -> prepared("one", 4, "数学"), result -> { });
    var cached = session.reviewCached("first", 2, 6000, () -> { throw new AssertionError(); }, result -> { });
    var second = session.reviewCached("second", 2, 6000, () -> prepared("two", 23, "Spring AI"), result -> { });
    assertThat(first.units().getFirst().id()).isEqualTo("E1");
    assertThat(second.units().getFirst().id()).isEqualTo("E2");
    assertThat(cached.text()).contains("E1").doesNotContain("数学");
    assertThat(session.citations("技术〔E2〕").sources()).singleElement()
        .satisfies(source -> assertThat(source.kbId()).isEqualTo(23L));
    assertThatThrownBy(() -> session.reviewCached("third", 2, 6000,
        () -> { throw new AssertionError(); }, result -> { })).isInstanceOf(BusinessException.class)
        .hasMessageContaining("BATCH_LIMIT");
    assertThat(session.failed()).isTrue();
  }

  @Test
  @DisplayName("同请求并发同批次只调用一次，不跨请求共享缓存")
  void concurrentCache() {
    var session = session();
    AtomicInteger calls = new AtomicInteger();
    Runnable action = () -> session.reviewCached("same", 2, 6000, () -> {
      calls.incrementAndGet();
      return prepared("one", 4, "原文");
    }, result -> { });
    CompletableFuture.allOf(CompletableFuture.runAsync(action), CompletableFuture.runAsync(action)).join();
    assertThat(calls.get()).isEqualTo(1);
    assertThat(session().acceptedUnits()).isEmpty();
  }

  @Test
  @DisplayName("失效授权删除缓存和可引用单元，不保留历史出处")
  void revocation() {
    var session = session();
    session.reviewCached("first", 2, 6000, () -> prepared("one", 4, "原文"), result -> { });
    session.reauthorize(Set.of(23L));
    assertThat(session.acceptedUnits()).isEmpty();
    assertThat(session.budgetSnapshot().blocks()).isEmpty();
    assertThat(session.citations("〔E1〕").sources()).isEmpty();
  }

  @Test
  @DisplayName("最后重标号后仍验证完整累计预算，不允许单批叠加绕过")
  void cumulativeLimit() {
    var session = session();
    session.reviewCached("first", 2, 6000, () -> prepared("one", 4, "例外规则".repeat(500)), result -> { });
    assertThatThrownBy(() -> session.reviewCached("second", 2, 1024,
        () -> prepared("two", 23, "其他规则".repeat(500)), result -> { }))
        .isInstanceOf(BusinessException.class).hasMessageContaining("INPUT_LIMIT");
    assertThat(session.acceptedUnits()).hasSize(1);
    assertThat(session.failed()).isTrue();
  }

  @Test
  @DisplayName("失败不缓存不放行；无归属和计费上下文不能构造生产会话")
  void failsClosed() {
    var session = session();
    assertThatThrownBy(() -> session.reviewCached("first", 2, 6000,
        () -> { throw RagEvidenceSession.failure("timeout"); }, result -> { }))
        .isInstanceOf(BusinessException.class);
    assertThat(session.failed()).isTrue();
    assertThatThrownBy(() -> new RagEvidenceSession(1L, null, false,
        ResolvedScope.ofSelection(List.of(4L)), session.callContext(), "问题"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("代码围栏与行内代码准确跳过，未知和畸形引用报警，按首次引用聚合")
  void citationGrammar() {
    var result = RagEvidenceSession.scanCitations("〔E2〕〔E1〕〔E2〕\n   ~~~~ java\n〔E3〕\n~~~\n"
        + "〔E3〕\n~~~~\n``〔E3〕`still code``\n〔E0〕〔E 1〕〔E1000000〕〔E9\n", Set.of("E1", "E2", "E3"));
    assertThat(result.unitIds()).containsExactly("E2", "E1");
    assertThat(result.invalid()).isTrue();
    var session = session();
    session.reviewCached("first", 2, 6000, () -> prepared("one", 4, "原文"), value -> { });
    assertThat(session.citations("没编号").warning()).contains("未包含有效资料引用");
    assertThat(session.citations("〔E1〕〔E99〕").warning()).contains("部分引用编号无效");
    assertThat(session.citations("〔E1〕〔E1〕").sources().getFirst().segments()).isEqualTo(1);
  }
}
