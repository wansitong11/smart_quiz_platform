-- AI 调用成本观测与控制：明细账本 + 价格表 + 操作预占 + 用户预算覆盖 + 日聚合
CREATE TABLE IF NOT EXISTS ai_call_events (
  id                BIGSERIAL PRIMARY KEY,
  request_id        VARCHAR(64)  NOT NULL,
  trace_id          VARCHAR(64),
  operation_id      VARCHAR(64)  NOT NULL,
  tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'default',
  user_id           BIGINT,
  feature           VARCHAR(32)  NOT NULL,
  operation         VARCHAR(32)  NOT NULL,
  source            VARCHAR(16)  NOT NULL DEFAULT 'SYNC',
  provider          VARCHAR(64)  NOT NULL,
  model             VARCHAR(128) NOT NULL,
  modality          VARCHAR(16)  NOT NULL,
  status            VARCHAR(24)  NOT NULL,
  error_class       VARCHAR(64),
  retry_index       INT          NOT NULL DEFAULT 0,
  latency_ms        BIGINT,
  queue_wait_ms     BIGINT,
  input_tokens      BIGINT,
  output_tokens     BIGINT,
  cached_tokens     BIGINT,
  image_count       INT,
  char_count        BIGINT,
  audio_seconds     NUMERIC(12,3),
  estimated_cost    NUMERIC(18,6),
  currency          VARCHAR(3)   NOT NULL DEFAULT 'USD',
  price_version     VARCHAR(32),
  estimated         BOOLEAN      NOT NULL DEFAULT TRUE,
  occurred_at       TIMESTAMPTZ  NOT NULL,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_call_occurred ON ai_call_events (occurred_at);
CREATE INDEX IF NOT EXISTS idx_ai_call_user      ON ai_call_events (user_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_ai_call_feature   ON ai_call_events (feature, occurred_at);
CREATE INDEX IF NOT EXISTS idx_ai_call_provider  ON ai_call_events (provider, occurred_at);
CREATE INDEX IF NOT EXISTS idx_ai_call_op        ON ai_call_events (operation_id);

CREATE TABLE IF NOT EXISTS ai_model_prices (
  id                    BIGSERIAL PRIMARY KEY,
  provider              VARCHAR(64)  NOT NULL,
  model                 VARCHAR(128) NOT NULL,
  modality              VARCHAR(16)  NOT NULL,
  input_price_per_1m    NUMERIC(18,6),
  output_price_per_1m   NUMERIC(18,6),
  cached_price_per_1m   NUMERIC(18,6),
  unit                  VARCHAR(8)   NOT NULL DEFAULT 'TOKEN',
  currency              VARCHAR(3)   NOT NULL DEFAULT 'USD',
  source                VARCHAR(8)   NOT NULL DEFAULT 'AUTO',
  effective_from        TIMESTAMPTZ  NOT NULL,
  effective_to          TIMESTAMPTZ,
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_model_prices_lookup
  ON ai_model_prices (provider, model, modality, effective_from);

CREATE TABLE IF NOT EXISTS ai_usage_operations (
  operation_id    VARCHAR(64) PRIMARY KEY,
  request_id      VARCHAR(64),
  trace_id        VARCHAR(64),
  tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
  user_id         BIGINT,
  feature         VARCHAR(32) NOT NULL,
  source          VARCHAR(16) NOT NULL DEFAULT 'SYNC',
  status          VARCHAR(16) NOT NULL,
  estimated_cost  NUMERIC(18,6) NOT NULL,
  actual_cost     NUMERIC(18,6),
  currency        VARCHAR(3) NOT NULL DEFAULT 'USD',
  started_at      TIMESTAMPTZ NOT NULL,
  settled_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ai_usage_op_user ON ai_usage_operations (user_id, started_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_op_feature ON ai_usage_operations (feature, started_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_op_status ON ai_usage_operations (status, started_at);

CREATE TABLE IF NOT EXISTS ai_user_budget_override (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  feature     VARCHAR(32),
  period      VARCHAR(8)  NOT NULL,
  limit_type  VARCHAR(8)  NOT NULL DEFAULT 'COST',
  soft_limit  NUMERIC(18,6),
  hard_limit  NUMERIC(18,6) NOT NULL,
  effective_from TIMESTAMPTZ NOT NULL,
  effective_to   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ai_budget_override_user
  ON ai_user_budget_override (user_id, effective_from);

CREATE TABLE IF NOT EXISTS ai_call_daily_agg (
  agg_date      DATE         NOT NULL,
  tenant_id     VARCHAR(64)  NOT NULL,
  user_id       BIGINT       NOT NULL,
  feature       VARCHAR(32)  NOT NULL,
  provider      VARCHAR(64)  NOT NULL,
  model         VARCHAR(128) NOT NULL,
  call_count    BIGINT       NOT NULL,
  success_count BIGINT       NOT NULL,
  input_tokens  BIGINT       NOT NULL,
  output_tokens BIGINT       NOT NULL,
  operation_count BIGINT     NOT NULL,
  estimated_cost NUMERIC(18,6) NOT NULL,
  PRIMARY KEY (agg_date, tenant_id, user_id, feature, provider, model)
);
