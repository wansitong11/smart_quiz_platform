-- =============================================================================
-- 资料出题从「面试题」改为「考卷题」
--   新增题型 / 选项 / 分值 / 解析四个字段，与模拟测验的题目结构对齐。
--   存量数据这四列为空 —— 作答端会按"无题型"退化成文本作答，不会影响老题目。
--   follow_ups_json 保留但不再写入（测验不做追问）。
-- =============================================================================

ALTER TABLE knowledge_base_questions ADD COLUMN IF NOT EXISTS question_type VARCHAR(32);
ALTER TABLE knowledge_base_questions ADD COLUMN IF NOT EXISTS options_json TEXT;
ALTER TABLE knowledge_base_questions ADD COLUMN IF NOT EXISTS full_score INTEGER;
ALTER TABLE knowledge_base_questions ADD COLUMN IF NOT EXISTS analysis TEXT;
