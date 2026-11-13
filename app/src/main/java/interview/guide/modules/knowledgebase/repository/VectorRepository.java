package interview.guide.modules.knowledgebase.repository;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 向量存储Repository
 * 负责向量数据的增删改查操作
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {
    
    private final JdbcTemplate jdbcTemplate;

    public record NeighborKey(long kbId, String parentDocumentId, int chunkIndex) { }
    public record NeighborChunk(String id, String content, Map<String, Object> metadata) { }

    public List<NeighborChunk> findNeighbors(List<NeighborKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        String values = keys.stream().map(key -> "(CAST(? AS text), CAST(? AS text), CAST(? AS text))")
            .collect(Collectors.joining(","));
        String sql = "SELECT v.id, v.content, v.metadata FROM vector_store v JOIN (VALUES "
            + values + ") AS k(kb_id, parent_id, chunk_index) ON v.metadata->>'kb_id' = k.kb_id"
            + " AND v.metadata->>'parent_document_id' = k.parent_id"
            + " AND v.metadata->>'chunk_index' = k.chunk_index"
            + " WHERE v.metadata->>'chunk_strategy' = 'section-neighbor-v2'";
        List<Object> arguments = new ArrayList<>();
        for (NeighborKey key : keys) {
            arguments.add(Long.toString(key.kbId()));
            arguments.add(key.parentDocumentId());
            arguments.add(Integer.toString(key.chunkIndex()));
        }
        ObjectMapper mapper = new ObjectMapper();
        return jdbcTemplate.query(sql, result -> {
            List<NeighborChunk> chunks = new ArrayList<>();
            while (result.next()) {
                try {
                    Map<String, Object> metadata = mapper.readValue(result.getString("metadata"),
                        new TypeReference<Map<String, Object>>() { });
                    chunks.add(new NeighborChunk(result.getString("id"), result.getString("content"), metadata));
                } catch (tools.jackson.core.JacksonException e) {
                    log.warn("邻居元数据解析失败: id={}", result.getString("id"), e);
                }
            }
            return chunks;
        }, arguments.toArray());
    }
    
    /**
     * 删除指定知识库的所有向量数据
     * 使用 SQL 直接删除，利用数据库索引和删除能力
     * <p>
     * Spring AI PgVectorStore 默认表名为 vector_store，元数据存储在 metadata 字段（JSONB类型）
     * 
     * @param knowledgeBaseId 知识库ID
     * @return 删除的行数
     */
    public int deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId);
        
        /* 
         * 注意：
         * 1. metadata 字段是 json 类型，不支持 jsonb_exists 函数。
         * 2. 使用 metadata->>'key' IS NOT NULL 来替代键存在性检查，这在 json/jsonb 下都有效。
         * 3. 这种写法完全避开了 PostgreSQL 的 '?' 操作符，不会引起 JDBC 占位符冲突。
         */
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;
        
        try {
            // 第一个参数转为 String 匹配 kb_id，第二个参数保持 Long 匹配 kb_id_long
            int deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId);
            
            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows);
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId);
            }
            
            return deletedRows;
            
        } catch (Exception e) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 抛出异常以触发事务回滚
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    /**
     * 删除指定向量化任务写入的临时向量数据。
     */
    public int deleteByVectorJobId(String jobId) {
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_vector_job_id' = ?
            """;
        try {
            int deletedRows = jdbcTemplate.update(sql, jobId);
            log.info("已清理临时向量数据: jobId={}, 删除行数={}", jobId, deletedRows);
            return deletedRows;
        } catch (Exception e) {
            log.error("清理临时向量数据失败: jobId={}, error={}", jobId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "清理临时向量数据失败");
        }
    }

    /**
     * 将临时向量任务提升为当前知识库的正式向量数据。
     */
    public int promoteVectorJob(Long knowledgeBaseId, String jobId) {
        String sql = """
            UPDATE vector_store
            SET metadata = (jsonb_set(
                    metadata::jsonb,
                    '{kb_id}',
                    to_jsonb(?::text),
                    true
                ) - 'kb_vector_job_id' - 'kb_target_id')::json
            WHERE metadata->>'kb_vector_job_id' = ?
            """;
        try {
            int updatedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), jobId);
            log.info("临时向量数据已提升为正式数据: kbId={}, jobId={}, 更新行数={}",
                knowledgeBaseId, jobId, updatedRows);
            return updatedRows;
        } catch (Exception e) {
            log.error("提升临时向量数据失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "提升临时向量数据失败");
        }
    }
}
