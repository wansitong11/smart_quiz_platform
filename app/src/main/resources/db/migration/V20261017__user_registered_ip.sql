-- =============================================================================
-- 自助注册的来源 IP：用于"同一网络 24 小时内最多注册 N 个账号"的配额控制
--   - 只有走 /api/auth/register 的自助注册才会写入（管理员建号留 NULL）
--   - 这个列同时把"自助注册"的账号和"管理员建号"区分开，
--     所以"全站每日注册上限"只统计自助注册，不会把管理员批量建号算进去
-- =============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS registered_ip VARCHAR(64);

COMMENT ON COLUMN users.registered_ip IS '自助注册来源 IP（管理员建号为 NULL）';

-- 按 IP + 时间统计配额用
CREATE INDEX IF NOT EXISTS idx_users_registered_ip_created
    ON users (registered_ip, created_at);
