package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.RagQueryTraceEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 问答轨迹仓储（榜单查询用）。
 */
@Repository
public interface RagQueryTraceRepository extends JpaRepository<RagQueryTraceEntity, Long> {

    /**
     * 未命中问题榜：同一问题出现次数排序。
     */
    @Query("SELECT t.question, COUNT(t), MAX(t.createdAt) FROM RagQueryTraceEntity t "
        + "WHERE t.outcome = :outcome AND t.createdAt > :since GROUP BY t.question "
        + "ORDER BY COUNT(t) DESC")
    List<Object[]> topNoHitQuestions(@Param("outcome") String outcome,
                                     @Param("since") LocalDateTime since,
                                     Pageable pageable);

    /**
     * 意图分布：RULE / TOOL / FALLBACK 各多少、平均耗时。
     */
    @Query("SELECT t.intent, COUNT(t), COALESCE(AVG(t.latencyMs), 0) FROM RagQueryTraceEntity t "
        + "WHERE t.createdAt > :since GROUP BY t.intent ORDER BY COUNT(t) DESC")
    List<Object[]> intentSummary(@Param("since") LocalDateTime since);

    /**
     * 窗口内被命中过的资料 ID（逗号分隔的原文，由 Service 拆分）。
     */
    @Query("SELECT t.retrievedKbIds FROM RagQueryTraceEntity t "
        + "WHERE t.createdAt > :since AND t.retrievedKbIds IS NOT NULL AND t.retrievedKbIds <> ''")
    List<String> findRetrievedKbIdsSince(@Param("since") LocalDateTime since);

    long countByCreatedAtAfter(LocalDateTime since);

    long countByOutcomeAndCreatedAtAfter(String outcome, LocalDateTime since);

    long countByIntentAndCreatedAtAfter(String intent, LocalDateTime since);
}
