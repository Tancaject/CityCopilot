-- 原子目的：确认运行时租约令牌仍未过期后延长 ZSET 到期分数，供长事务持续证明迁移屏障所有权。
-- KEYS[1]：业务写或分片准入租约 ZSET；ARGV[1]：租约令牌；ARGV[2]：续租 TTL（毫秒）。
-- 返回值：0=续租成功；13=成员不存在或按 Redis TIME 已过期。
local redis_time = redis.call('time')
local now_millis = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
local expires_at = redis.call('zscore', KEYS[1], ARGV[1])
if not expires_at or tonumber(expires_at) <= now_millis then
  return 13
end
-- XX 保证续租不会在所有权丢失后意外创建一个新租约。
redis.call('zadd', KEYS[1], 'XX', now_millis + tonumber(ARGV[2]), ARGV[1])
return 0
