local revision = redis.call('incr', KEYS[1])
redis.call('psetex', KEYS[3], ARGV[1], tostring(revision))
redis.call('del', KEYS[2])
return revision
