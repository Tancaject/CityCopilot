local member = ARGV[1]
local sourceId = ARGV[2]
local group = ARGV[3]
redis.call('srem', KEYS[1], member)
redis.call('del', KEYS[3])
local acknowledged = redis.call('xack', KEYS[2], group, sourceId)
redis.call('xdel', KEYS[2], sourceId)
return acknowledged
