redis.call('zrem', KEYS[1], ARGV[1])
return 0
