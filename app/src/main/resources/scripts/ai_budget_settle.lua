-- 结算：撤销预占、按真实金额入账，并把真实金额折算成当月累计百分比。
-- KEYS[1]=used, KEYS[2]=reserved, KEYS[3]=percent
-- ARGV[1]=estimatedMicro, ARGV[2]=actualMicro, ARGV[3]=ttlSeconds
-- ARGV[4]=quotaMicro, ARGV[5]=percentDeltaMicro
local used = tonumber(redis.call('GET', KEYS[1]) or '0')
local reserved = tonumber(redis.call('GET', KEYS[2]) or '0')
local est = tonumber(ARGV[1])
local actual = tonumber(ARGV[2])
local quota = tonumber(ARGV[4])

local percent = redis.call('GET', KEYS[3])
if not percent then
  local initial = 0
  if quota > 0 then
    initial = math.floor(used * 100000000 / quota + 0.5)
  end
  redis.call('SET', KEYS[3], initial)
  redis.call('EXPIRE', KEYS[3], ARGV[3])
end

redis.call('DECRBY', KEYS[2], est)
if reserved - est < 0 then redis.call('SET', KEYS[2], 0) end
redis.call('INCRBY', KEYS[1], actual)
local percentDelta = tonumber(ARGV[5])
if percentDelta > 0 then
  redis.call('INCRBY', KEYS[3], percentDelta)
  redis.call('EXPIRE', KEYS[3], ARGV[3])
end
redis.call('EXPIRE', KEYS[1], ARGV[3])
redis.call('EXPIRE', KEYS[2], ARGV[3])
return 0
