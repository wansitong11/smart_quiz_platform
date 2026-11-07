-- =============================================================================
-- 错题本 · 持久化状态、原题快照与回写幂等标记
-- 错题按 owner_id + question_key 唯一聚合；question_snapshot 保存学生可见原题、
-- 材料上下文与内部答案键，源会话被删除后仍可回顾和重练。
-- =============================================================================

CREATE TABLE IF NOT EXISTS wrong_questions (
  id                    BIGSERIAL PRIMARY KEY,
  owner_id              BIGINT NOT NULL,
  question_key          VARCHAR(64) NOT NULL,
  skill_id              VARCHAR(64) NOT NULL,
  exam_code             VARCHAR(64),
  exam_name             VARCHAR(64),
  subject_name          VARCHAR(64),
  knowledge_point_key   VARCHAR(64),
  knowledge_point_name  VARCHAR(64),
  question_type         VARCHAR(32),
  stem                  TEXT NOT NULL,
  question_snapshot     TEXT NOT NULL,
  status                VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  correct_count         INTEGER NOT NULL DEFAULT 0,
  wrong_count           INTEGER NOT NULL DEFAULT 0,
  last_score_rate       INTEGER,
  last_user_answer      TEXT,
  last_wrong_at         TIMESTAMPTZ,
  last_correct_at       TIMESTAMPTZ,
  mastered_at           TIMESTAMPTZ,
  archived_at           TIMESTAMPTZ,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_wrong_questions_owner_key
  ON wrong_questions (owner_id, question_key);

CREATE INDEX IF NOT EXISTS idx_wrong_questions_owner_status
  ON wrong_questions (owner_id, status);

CREATE INDEX IF NOT EXISTS idx_wrong_questions_owner_subject
  ON wrong_questions (owner_id, subject_name);

CREATE INDEX IF NOT EXISTS idx_wrong_questions_owner_knowledge
  ON wrong_questions (owner_id, knowledge_point_key);

CREATE TABLE IF NOT EXISTS wrong_question_backfills (
  owner_id      BIGINT PRIMARY KEY,
  backfilled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS wrong_question_attempt_events (
  id             BIGSERIAL PRIMARY KEY,
  owner_id       BIGINT NOT NULL,
  session_id     VARCHAR(36) NOT NULL,
  question_index INTEGER NOT NULL,
  processed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_wrong_attempt_event_session_question
  ON wrong_question_attempt_events (session_id, question_index);
