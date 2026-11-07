-- 当月首次读取时，用数据库账本补齐 Redis 的已用金额和累计百分比。
-- 只补历史缺口：数据库总额大于 Redis 已用时才抬高 used；percent 取
-- “数据库总额 / 当前人均额度”与已有 percent 的较大值，避免回跳。
-- KEYS[1]=used, KEYS[2]=percent, KEYS[3]=seeded
-- ARGV[1]=dbUsedMicro, ARGV[2]=quotaMicro, ARGV[3]=ttlSeconds, ARGV[4]=percentScale
local dbUsed = tonumber(ARGV[1] or '0')
local quota = tonumber(ARGV[2] or '0')
local ttl = tonumber(ARGV[3])
local percentScale = tonumber(ARGV[4])

local currentUsed = tonumber(redis.call('GET', KEYS[1]) or '0')
local targetUsed = currentUsed
if dbUsed > targetUsed then
  targetUsed = dbUsed
end

redis.call('SET', KEYS[1], targetUsed, 'EX', ttl)

local targetPercent = 0
if quota > 0 and targetUsed > 0 then
  targetPercent = math.floor(targetUsed * 100 * percentScale / quota + 0.5)
end

local currentPercent = redis.call('GET', KEYS[2])
if not currentPercent then
  redis.call('SET', KEYS[2], targetPercent, 'EX', ttl)
elseif targetPercent > tonumber(currentPercent) then
  redis.call('SET', KEYS[2], targetPercent, 'EX', ttl)
end

redis.call('SET', KEYS[3], '1', 'EX', ttl)
return 0
