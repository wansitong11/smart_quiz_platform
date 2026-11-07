-- 用户档位与月度总量池：免费用户共享月度总量，授权用户豁免且不占池。
ALTER TABLE users ADD COLUMN IF NOT EXISTS tier VARCHAR(16) NOT NULL DEFAULT 'FREE';

ALTER TABLE ai_usage_operations ADD COLUMN IF NOT EXISTS exempt BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS ai_quota_config (
  id                BIGINT PRIMARY KEY,
  monthly_total_cny NUMERIC(18,6) NOT NULL,
  updated_by        BIGINT,
  updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

INSERT INTO ai_quota_config (id, monthly_total_cny, updated_at)
VALUES (1, 100.000000, now())
ON CONFLICT (id) DO NOTHING;
