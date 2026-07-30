local member = ARGV[1]
local sourceId = ARGV[2]
local group = ARGV[3]
redis.call('srem', KEYS[1], member)
redis.call('del', KEYS[3])
return redis.call('xack', KEYS[2], group, sourceId)
