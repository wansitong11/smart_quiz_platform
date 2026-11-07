-- 用户模型绑定：管理员按用户覆盖文字/识图模型
CREATE TABLE IF NOT EXISTS ai_user_model_override (
  id           BIGSERIAL PRIMARY KEY,
  user_id      BIGINT      NOT NULL,
  modality     VARCHAR(16) NOT NULL,
  provider_id  VARCHAR(64) NOT NULL,
  updated_by   BIGINT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_user_model_override UNIQUE (user_id, modality)
);

CREATE INDEX IF NOT EXISTS idx_user_model_override_user
  ON ai_user_model_override (user_id);
