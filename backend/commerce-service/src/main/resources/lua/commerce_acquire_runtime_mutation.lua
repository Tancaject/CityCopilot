-- 原子目的：在全局路由仍为 ACTIVE 且无运维操作者时，为普通业务写登记一个会过期的运行时租约。
-- KEYS[1]：全局路由控制记录；KEYS[2]：活跃业务写租约 ZSET；KEYS[3]：路由运维租约键。
-- ARGV[1]：预期 ACTIVE 控制值前缀；ARGV[2]：业务租约令牌；ARGV[3]：租约 TTL（毫秒）。
-- 返回值：0=取得租约；11=控制记录缺失或不再 ACTIVE；12=路由运维操作正在执行。
local control = redis.call('get', KEYS[1])
if not control or string.sub(control, 1, string.len(ARGV[1])) ~= ARGV[1] then
  return 11
end
-- 先检查运维租约，避免迁移屏障建立期间再放入新的业务写。
if redis.call('exists', KEYS[3]) == 1 then
  return 12
end
-- Redis TIME 与过期成员清理保证多实例对“仍在途”的判断一致。
local redis_time = redis.call('time')
local now_millis = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
redis.call('zremrangebyscore', KEYS[2], '-inf', now_millis)
redis.call('zadd', KEYS[2], now_millis + tonumber(ARGV[3]), ARGV[2])
return 0
