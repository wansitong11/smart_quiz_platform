-- D 阶段：面试会话异步出题状态、执行代次、恢复计数与请求快照
ALTER TABLE practice_sessions
  ADD COLUMN IF NOT EXISTS question_status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
  ADD COLUMN IF NOT EXISTS question_error VARCHAR(500),
  ADD COLUMN IF NOT EXISTS question_attempt_id VARCHAR(36),
  ADD COLUMN IF NOT EXISTS question_updated_at TIMESTAMP(6),
  ADD COLUMN IF NOT EXISTS question_recovery_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS question_gen_request_json TEXT;

CREATE INDEX IF NOT EXISTS idx_practice_session_question_status_updated
  ON practice_sessions (question_status, question_updated_at);
