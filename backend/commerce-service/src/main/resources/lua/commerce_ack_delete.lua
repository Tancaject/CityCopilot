-- ACK and delete one terminal record atomically on its shard stream.
local acknowledged = redis.call('xack', KEYS[1], ARGV[1], ARGV[2])
redis.call('xdel', KEYS[1], ARGV[2])
return acknowledged
