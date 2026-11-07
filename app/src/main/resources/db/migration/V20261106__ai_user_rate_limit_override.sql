-- 管理员按用户配置 RPM / TPM / 并发限流覆盖；成本覆盖继续使用 ai_user_budget_override。
CREATE TABLE IF NOT EXISTS ai_user_rate_limit_override (
  id                BIGSERIAL PRIMARY KEY,
  user_id           BIGINT      NOT NULL,
  feature           VARCHAR(32),
  rpm_limit         INT,
  tpm_limit         BIGINT,
  concurrency_limit INT,
  effective_from    TIMESTAMPTZ NOT NULL,
  effective_to      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ai_rate_override_user
  ON ai_user_rate_limit_override (user_id, effective_from);

CREATE INDEX IF NOT EXISTS idx_ai_rate_override_feature
  ON ai_user_rate_limit_override (user_id, feature);
