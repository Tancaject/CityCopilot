-- 原子目的：仅在 Voucher 分片门禁仍属于预期 ACTIVE 代际时，登记一个会过期的准入写租约。
-- KEYS[1]：分片准入路由门禁；KEYS[2]：该分片的活跃准入租约 ZSET。
-- ARGV[1]：预期 ACTIVE 门禁值；ARGV[2]：本请求唯一租约令牌；ARGV[3]：租约 TTL（毫秒）。
-- 返回值：0=取得租约；11=门禁缺失或已切换，禁止新的秒杀准入。
if redis.call('get', KEYS[1]) ~= ARGV[1] then
  return 11
end
-- 使用 Redis TIME 统一多实例时钟，并先清除过期成员，排空计数只反映仍有效的请求。
local redis_time = redis.call('time')
local now_millis = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
redis.call('zremrangebyscore', KEYS[2], '-inf', now_millis)
redis.call('zadd', KEYS[2], now_millis + tonumber(ARGV[3]), ARGV[2])
return 0
