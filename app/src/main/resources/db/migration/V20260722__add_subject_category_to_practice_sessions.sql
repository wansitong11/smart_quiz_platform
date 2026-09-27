-- 为练习会话表新增知识板块字段（组卷来源题库 category）。
-- V1 已包含该字段；本迁移用于未执行 V1 的既有库基线后补列。
DO $$
BEGIN
  IF to_regclass('public.practice_sessions') IS NOT NULL THEN
    IF NOT EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = 'public'
        AND table_name = 'practice_sessions'
        AND column_name = 'subject_category'
    ) THEN
      ALTER TABLE practice_sessions
        ADD COLUMN subject_category VARCHAR(64);
    END IF;

    COMMENT ON COLUMN practice_sessions.subject_category IS '知识板块（来自题库 category，普通练习为 NULL）';
  END IF;
END
$$;
