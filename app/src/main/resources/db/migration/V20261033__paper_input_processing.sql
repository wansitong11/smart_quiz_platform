-- E 阶段：上传原始文件持久化与异步输入处理状态
ALTER TABLE practice_papers
  ADD COLUMN IF NOT EXISTS input_status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
  ADD COLUMN IF NOT EXISTS input_error VARCHAR(500),
  ADD COLUMN IF NOT EXISTS input_attempt_id VARCHAR(36),
  ADD COLUMN IF NOT EXISTS input_updated_at TIMESTAMP(6),
  ADD COLUMN IF NOT EXISTS input_recovery_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS paper_input_files (
  id BIGSERIAL PRIMARY KEY,
  paper_id BIGINT NOT NULL REFERENCES practice_papers(id) ON DELETE CASCADE,
  seq INT NOT NULL,
  storage_key VARCHAR(500) NOT NULL,
  filename VARCHAR(500),
  content_type VARCHAR(200),
  size_bytes BIGINT,
  CONSTRAINT uq_paper_input_files_paper_seq UNIQUE (paper_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_paper_input_files_paper ON paper_input_files(paper_id);
CREATE INDEX IF NOT EXISTS idx_practice_paper_input_status_updated
  ON practice_papers (input_status, input_updated_at);
