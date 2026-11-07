-- 预占：已用 + 已预占 + 本次估算 不超过硬上限；超过软上限返回 1（放行但告警）。
-- KEYS[1]=used, KEYS[2]=reserved
-- ARGV[1]=estimateMicro, ARGV[2]=hardMicro, ARGV[3]=softMicro, ARGV[4]=ttlSeconds
local used = tonumber(redis.call('GET', KEYS[1]) or '0')
local reserved = tonumber(redis.call('GET', KEYS[2]) or '0')
local est = tonumber(ARGV[1])
local hard = tonumber(ARGV[2])
local soft = tonumber(ARGV[3])

if used + reserved + est > hard then
  return -1
end

redis.call('INCRBY', KEYS[2], est)
redis.call('EXPIRE', KEYS[1], ARGV[4])
redis.call('EXPIRE', KEYS[2], ARGV[4])

if used + reserved + est > soft then
  return 1
end
return 0
