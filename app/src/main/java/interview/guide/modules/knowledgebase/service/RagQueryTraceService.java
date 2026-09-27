package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagQueryTraceDTO;
import interview.guide.modules.knowledgebase.model.RagQueryTraceEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagQueryTraceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 问答轨迹服务：异步落库 + 运营榜单查询。
 * <p>
 * 落库走虚拟线程、失败只记日志，绝不能影响 SSE 响应；
 * 关闭开关（{@code app.ai.rag.trace.enabled=false}）时直接丢弃。
 * </p>
 */
@Slf4j
@Service
public class RagQueryTraceService {

    private static final int MAX_QUESTION_LENGTH = 500;
    private static final int MAX_REWRITTEN_LENGTH = 500;

    private final RagQueryTraceRepository traceRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService traceExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public RagQueryTraceService(RagQueryTraceRepository traceRepository,
                                KnowledgeBaseRepository knowledgeBaseRepository,
                                KnowledgeBaseQueryProperties properties,
                                @Autowired(required = false) ObjectMapper objectMapper) {
        this.traceRepository = traceRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步记录一次问答轨迹；任何异常都只记日志。
     */
    public void recordAsync(RagQueryTraceCollector collector) {
        if (collector == null || !properties.getTrace().isEnabled()) {
            return;
        }
        traceExecutor.execute(() -> {
            try {
                record(collector);
            } catch (Exception e) {
                log.warn("记录问答轨迹失败: sessionId={}, error={}", collector.sessionId(), e.getMessage());
            }
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void record(RagQueryTraceCollector collector) {
        RagQueryTraceEntity entity = new RagQueryTraceEntity();
        entity.setSessionId(collector.sessionId());
        entity.setQuestion(truncate(collector.question(), MAX_QUESTION_LENGTH));
        entity.setRewrittenQuery(truncate(collector.rewrittenQuery(), MAX_REWRITTEN_LENGTH));
        entity.setScopeDescription(truncate(collector.scopeDescription(), 128));
        entity.setIntent(collector.intent());
        entity.setToolsJson(toToolsJson(collector));
        entity.setRetrievedKbIds(joinKbIds(collector.retrievedKbIds()));
        entity.setTopScore(collector.topScore());
        entity.setOutcome(collector.outcome());
        entity.setLatencyMs(collector.latencyMs());
        entity.setLlmProvider(truncate(collector.llmProvider(), 50));
        entity.setCreatedAt(LocalDateTime.now());
        traceRepository.save(entity);
    }

    /**
     * 路由健康度总览（默认最近 7 天）。
     */
    public RagQueryTraceDTO.TraceSummary summarize(int days) {
        LocalDateTime since = since(days);
        long total = traceRepository.countByCreatedAtAfter(since);
        long noHit = traceRepository.countByOutcomeAndCreatedAtAfter(
            RagQueryTraceEntity.OUTCOME_NO_HIT, since);
        long toolError = traceRepository.countByOutcomeAndCreatedAtAfter(
            RagQueryTraceEntity.OUTCOME_TOOL_ERROR, since);

        List<RagQueryTraceDTO.IntentStat> intents = new ArrayList<>();
        for (Object[] row : traceRepository.intentSummary(since)) {
            intents.add(new RagQueryTraceDTO.IntentStat(
                (String) row[0],
                ((Number) row[1]).longValue(),
                Math.round(((Number) row[2]).doubleValue())
            ));
        }

        double refusalRate = total == 0 ? 0 : (double) (noHit + toolError) / total;
        return new RagQueryTraceDTO.TraceSummary(total, noHit, toolError, refusalRate, intents);
    }

    /**
     * 未命中问题榜：查不到答案的问题，按出现次数排序。
     */
    public List<RagQueryTraceDTO.NoHitQuestion> topNoHitQuestions(int days, int limit) {
        List<RagQueryTraceDTO.NoHitQuestion> result = new ArrayList<>();
        for (Object[] row : traceRepository.topNoHitQuestions(
            RagQueryTraceEntity.OUTCOME_NO_HIT, since(days), PageRequest.of(0, limit))) {
            result.add(new RagQueryTraceDTO.NoHitQuestion(
                (String) row[0],
                ((Number) row[1]).longValue(),
                (LocalDateTime) row[2]
            ));
        }
        return result;
    }

    /**
     * 统计窗口内从未被命中的资料——可能是分块、命名或内容不适合检索。
     */
    public List<RagQueryTraceDTO.UnusedKnowledgeBase> unusedKnowledgeBases(int days) {
        Set<Long> hitIds = new LinkedHashSet<>();
        for (String raw : traceRepository.findRetrievedKbIdsSince(since(days))) {
            for (String part : raw.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    hitIds.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    // 轨迹数据损坏时跳过
                }
            }
        }

        List<RagQueryTraceDTO.UnusedKnowledgeBase> result = new ArrayList<>();
        for (KnowledgeBaseEntity kb : knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()) {
            if (hitIds.contains(kb.getId())) {
                continue;
            }
            result.add(new RagQueryTraceDTO.UnusedKnowledgeBase(
                kb.getId(), kb.getName(), kb.getCategoryCode(), kb.getChunkCount()));
        }
        return result;
    }

    private LocalDateTime since(int days) {
        int window = days > 0 ? days : 7;
        return LocalDateTime.now().minusDays(window);
    }

    private String toToolsJson(RagQueryTraceCollector collector) {
        if (objectMapper == null || collector.toolCalls().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(collector.toolCalls());
        } catch (Exception e) {
            log.warn("序列化工具调用轨迹失败: {}", e.getMessage());
            return null;
        }
    }

    private String joinKbIds(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return null;
        }
        return kbIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
