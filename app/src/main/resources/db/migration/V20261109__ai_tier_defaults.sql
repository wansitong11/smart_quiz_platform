-- 用户档位默认模型：免费用户 3.7，授权用户与系统任务 3.8。
-- 复制 dashscope 的加密 Key，避免新增 Provider 因空 Key 被判定为不可用。
INSERT INTO llm_provider_config (
  id, base_url, api_key_ciphertext, api_key_nonce, model,
  embedding_model, embedding_dimensions, supports_embedding, supports_vision,
  temperature, enabled, builtin, created_at, updated_at
)
SELECT
  'dashscope-3.7', base_url, api_key_ciphertext, api_key_nonce, 'qwen3.7-flash',
  NULL, NULL, FALSE, FALSE, NULL, TRUE, TRUE, now(), now()
FROM llm_provider_config
WHERE id = 'dashscope'
ON CONFLICT (id) DO NOTHING;

INSERT INTO llm_provider_config (
  id, base_url, api_key_ciphertext, api_key_nonce, model,
  embedding_model, embedding_dimensions, supports_embedding, supports_vision,
  temperature, enabled, builtin, created_at, updated_at
)
SELECT
  'dashscope-3.7-vl', base_url, api_key_ciphertext, api_key_nonce, 'qwen3.7-flash',
  NULL, NULL, FALSE, TRUE, NULL, TRUE, TRUE, now(), now()
FROM llm_provider_config
WHERE id = 'dashscope'
ON CONFLICT (id) DO NOTHING;

-- 兼容旧版本默认值：只修正历史默认文本模型，不覆盖管理员后来手工改过的其他模型。
UPDATE llm_provider_config
SET model = 'qwen3.8-flash', updated_at = now()
WHERE id = 'dashscope'
  AND model IN ('qwen3.5-122b-a10b', 'qwen3.5-flash');

-- 旧版“免费模型自动激活”可能把全局默认改到免费 Provider；这里只回收这些历史默认值。
UPDATE llm_global_setting
SET default_chat_provider_id = 'dashscope', updated_at = now()
WHERE id = 1
  AND default_chat_provider_id IN (
    'siliconflow-free', 'glm-free', 'siliconflow-cheap', 'siliconflow'
  );

UPDATE llm_global_setting
SET default_vision_provider_id = 'dashscope-vl', updated_at = now()
WHERE id = 1
  AND default_vision_provider_id IN ('glm-vl-free', 'siliconflow-vl');
