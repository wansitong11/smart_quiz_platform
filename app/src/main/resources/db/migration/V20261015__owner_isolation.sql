-- =============================================================================
-- 用户数据隔离：给业务数据加归属人，并把「全局去重」改成「按归属人去重」
--
-- 背景（不改这两条唯一约束，隔离就是漏的）：
--   practice_papers.file_hash / knowledge_bases.file_hash 原本是全局 UNIQUE，
--   第二个用户上传同一份文件会直接命中第一个用户的记录 —— 等于不用猜 id 就能串数据。
-- =============================================================================

-- ---------- 1. 归属列（先可空，回填后再由代码按"无归属不可见"处理） ----------
ALTER TABLE practice_papers   ADD COLUMN IF NOT EXISTS owner_id BIGINT;
ALTER TABLE practice_sessions ADD COLUMN IF NOT EXISTS owner_id BIGINT;
ALTER TABLE knowledge_bases   ADD COLUMN IF NOT EXISTS owner_id BIGINT;
ALTER TABLE rag_chat_sessions ADD COLUMN IF NOT EXISTS owner_id BIGINT;
ALTER TABLE study_plans       ADD COLUMN IF NOT EXISTS owner_id BIGINT;
ALTER TABLE oral_practice_sessions ADD COLUMN IF NOT EXISTS owner_id BIGINT;

-- ---------- 2. 资料库可见性：PRIVATE（默认，仅归属人）/ SHARED（全局共享） ----------
-- 管理员可上传两种；普通用户只能建 PRIVATE。
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';

-- ---------- 3. 存量数据划给 admin（决策 3） ----------
UPDATE practice_papers   SET owner_id = (SELECT id FROM users WHERE username = 'admin') WHERE owner_id IS NULL;
UPDATE practice_sessions SET owner_id = (SELECT id FROM users WHERE username = 'admin') WHERE owner_id IS NULL;
UPDATE knowledge_bases   SET owner_id = (SELECT id FROM users WHERE username = 'admin') WHERE owner_id IS NULL;
UPDATE rag_chat_sessions SET owner_id = (SELECT id FROM users WHERE username = 'admin') WHERE owner_id IS NULL;
UPDATE study_plans       SET owner_id = (SELECT id FROM users WHERE username = 'admin') WHERE owner_id IS NULL;
UPDATE oral_practice_sessions SET owner_id = (SELECT id FROM users WHERE username = 'admin') WHERE owner_id IS NULL;

-- ---------- 4. 去重从"全局"改为"按归属人"（决策 4：同一文件各自存一份） ----------
ALTER TABLE practice_papers DROP CONSTRAINT IF EXISTS idx_practice_paper_hash;
CREATE UNIQUE INDEX IF NOT EXISTS idx_practice_paper_owner_hash ON practice_papers (owner_id, file_hash);

ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS idx_kb_hash;
CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_owner_hash ON knowledge_bases (owner_id, file_hash);

-- ---------- 5. 查询索引 ----------
CREATE INDEX IF NOT EXISTS idx_practice_papers_owner   ON practice_papers (owner_id);
CREATE INDEX IF NOT EXISTS idx_practice_sessions_owner ON practice_sessions (owner_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_bases_owner   ON knowledge_bases (owner_id);
CREATE INDEX IF NOT EXISTS idx_rag_chat_sessions_owner ON rag_chat_sessions (owner_id);
