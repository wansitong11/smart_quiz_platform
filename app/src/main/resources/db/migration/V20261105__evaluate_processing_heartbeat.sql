-- 批改任务心跳与执行代次：用于识别进程崩溃后永久 PROCESSING 的会话
ALTER TABLE practice_sessions
  ADD COLUMN IF NOT EXISTS evaluate_attempt_id VARCHAR(36),
  ADD COLUMN IF NOT EXISTS evaluate_heartbeat_at TIMESTAMP(6);

CREATE INDEX IF NOT EXISTS idx_practice_session_evaluate_heartbeat
  ON practice_sessions (evaluate_status, evaluate_heartbeat_at);
