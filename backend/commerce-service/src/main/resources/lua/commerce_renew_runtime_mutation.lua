local redis_time = redis.call('time')
local now_millis = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
local expires_at = redis.call('zscore', KEYS[1], ARGV[1])
if not expires_at or tonumber(expires_at) <= now_millis then
  return 13
end
redis.call('zadd', KEYS[1], 'XX', now_millis + tonumber(ARGV[2]), ARGV[1])
return 0
