local redis_time = redis.call('time')
local now_millis = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
redis.call('zremrangebyscore', KEYS[1], '-inf', now_millis)
return redis.call('zcard', KEYS[1])
