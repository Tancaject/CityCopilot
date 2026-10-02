-- 原子目的：清除已过期的运行时租约并返回当前真实在途数量，供路由排空屏障判断。
-- KEYS[1]：业务写或分片准入租约 ZSET；无 ARGV。
-- 返回值：清理后的 ZSET 成员数，非负整数。
local redis_time = redis.call('time')
local now_millis = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
redis.call('zremrangebyscore', KEYS[1], '-inf', now_millis)
return redis.call('zcard', KEYS[1])
