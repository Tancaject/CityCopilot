-- 原子目的：为库存恢复登记运行时租约；恢复可在 ACTIVE 或 DRAINING 代际继续，但不能穿越路由运维屏障。
-- KEYS[1]：全局路由控制记录；KEYS[2]：活跃业务写租约 ZSET；KEYS[3]：路由运维租约键。
-- ARGV[1]：ACTIVE 前缀；ARGV[2]：DRAINING 前缀；ARGV[3]：恢复租约令牌；ARGV[4]：租约 TTL（毫秒）。
-- 返回值：0=取得租约；11=控制状态既非 ACTIVE 也非 DRAINING；12=路由运维操作正在执行。
local control = redis.call('get', KEYS[1])
local active = control and string.sub(control, 1, string.len(ARGV[1])) == ARGV[1]
local draining = control and string.sub(control, 1, string.len(ARGV[2])) == ARGV[2]
if not active and not draining then
  return 11
end
-- DRAINING 允许旧在途恢复收尾，但运维租约存在时必须暂停，防止与激活/迁移并发修改。
if redis.call('exists', KEYS[3]) == 1 then
  return 12
end
local redis_time = redis.call('time')
local now_millis = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
redis.call('zremrangebyscore', KEYS[2], '-inf', now_millis)
redis.call('zadd', KEYS[2], now_millis + tonumber(ARGV[4]), ARGV[3])
return 0
