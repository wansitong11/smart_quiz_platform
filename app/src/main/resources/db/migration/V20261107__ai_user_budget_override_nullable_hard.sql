-- 日软上限与日硬上限可分别配置，未设置的维度应回退到全局或系统默认值。
ALTER TABLE ai_user_budget_override ALTER COLUMN hard_limit DROP NOT NULL;
