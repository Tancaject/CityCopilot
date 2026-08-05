local control = redis.call('get', KEYS[1])
local active = control and string.sub(control, 1, string.len(ARGV[1])) == ARGV[1]
local draining = control and string.sub(control, 1, string.len(ARGV[2])) == ARGV[2]
if not active and not draining then
  return 11
end
if redis.call('exists', KEYS[3]) == 1 then
  return 12
end
local redis_time = redis.call('time')
local now_millis = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
redis.call('zremrangebyscore', KEYS[2], '-inf', now_millis)
redis.call('zadd', KEYS[2], now_millis + tonumber(ARGV[4]), ARGV[3])
return 0
