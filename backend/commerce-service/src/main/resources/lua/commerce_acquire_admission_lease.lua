if redis.call('get', KEYS[1]) ~= ARGV[1] then
  return 11
end
local redis_time = redis.call('time')
local now_millis = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
redis.call('zremrangebyscore', KEYS[2], '-inf', now_millis)
redis.call('zadd', KEYS[2], now_millis + tonumber(ARGV[3]), ARGV[2])
return 0
