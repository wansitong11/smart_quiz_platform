-- =============================================================================
-- 模拟测验模块（原「模拟面试」改造）
--   1. 记录组卷参数快照，用于「再测一次」与学情统计
--   2. 为错题本查询加索引（错题本按 skill + 完成状态扫描）
-- 说明：题目本身仍复用 practice_sessions.paper_json（PaperTree），不新建表。
--       错题判定在查询时按「得分 < 该题满分」推导，不需要给 practice_answers 加列。
-- =============================================================================

ALTER TABLE practice_sessions ADD COLUMN IF NOT EXISTS quiz_config_json TEXT;

CREATE INDEX IF NOT EXISTS idx_practice_sessions_skill_status
  ON practice_sessions (skill_id, status);
