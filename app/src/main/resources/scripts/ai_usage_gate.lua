-- RPM / TPM / 并发 三合一闸门，全部通过才放行；任一失败回滚已扣减的计数。
-- KEYS[1]=rpm, KEYS[2]=tpm, KEYS[3]=conc
-- ARGV[1]=rpmLimit, ARGV[2]=tpmLimit, ARGV[3]=concLimit, ARGV[4]=windowMs, ARGV[5]=estimateTokens
local rpm = tonumber(ARGV[1])
local tpm = tonumber(ARGV[2])
local conc = tonumber(ARGV[3])
local window = tonumber(ARGV[4])
local tokens = tonumber(ARGV[5])

local c = redis.call('INCR', KEYS[3])
if c == 1 then redis.call('PEXPIRE', KEYS[3], 3600000) end
if c > conc then
  redis.call('DECR', KEYS[3])
  return -3
end

local r = redis.call('INCR', KEYS[1])
if r == 1 then redis.call('PEXPIRE', KEYS[1], window) end
if r > rpm then
  redis.call('DECR', KEYS[1])
  redis.call('DECR', KEYS[3])
  return -1
end

local t = redis.call('INCRBY', KEYS[2], tokens)
if t == tokens then redis.call('PEXPIRE', KEYS[2], window) end
if t > tpm then
  redis.call('DECRBY', KEYS[2], tokens)
  redis.call('DECR', KEYS[1])
  redis.call('DECR', KEYS[3])
  return -2
end

return 0
