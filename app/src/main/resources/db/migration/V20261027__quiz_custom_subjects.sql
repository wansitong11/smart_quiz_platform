-- =============================================================================
-- 模拟测验 · 自定义科目
-- 系统科目继续放 classpath（skills/_exams.yml + skills/{subjectId}/skill.meta.yml），
-- 自定义科目落库并归属到用户（软删除，历史会话靠 quiz_config_json 快照照常展示）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS quiz_subjects (
  id            BIGSERIAL PRIMARY KEY,
  subject_code  VARCHAR(64) NOT NULL,
  owner_id      BIGINT NOT NULL,
  exam_code     VARCHAR(64) NOT NULL,
  name          VARCHAR(64) NOT NULL,
  config_json   TEXT NOT NULL,
  deleted_at    TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_quiz_subjects_owner_code
  ON quiz_subjects (owner_id, subject_code);

CREATE INDEX IF NOT EXISTS idx_quiz_subjects_owner_exam
  ON quiz_subjects (owner_id, exam_code);
