local acquired = redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])
if acquired then
  return 0
end
return 12
