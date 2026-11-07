-- 全额退回预占。
-- KEYS[1]=reserved, ARGV[1]=estimatedMicro, ARGV[2]=ttlSeconds
local reserved = tonumber(redis.call('GET', KEYS[1]) or '0')
local est = tonumber(ARGV[1])
redis.call('DECRBY', KEYS[1], est)
if reserved - est < 0 then redis.call('SET', KEYS[1], 0) end
redis.call('EXPIRE', KEYS[1], ARGV[2])
return 0
