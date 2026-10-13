-- 问答轨迹也属于用户数据：榜单里含"未命中问题"原文，不能跨用户可见
ALTER TABLE rag_query_traces ADD COLUMN IF NOT EXISTS owner_id BIGINT;
UPDATE rag_query_traces SET owner_id = (SELECT id FROM users WHERE username = 'admin') WHERE owner_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_rag_query_traces_owner ON rag_query_traces (owner_id);
