-- 释放一个并发槽位，下限钳到 0。
local c = redis.call('DECR', KEYS[1])
if c < 0 then
  redis.call('SET', KEYS[1], 0)
  return 0
end
return c
