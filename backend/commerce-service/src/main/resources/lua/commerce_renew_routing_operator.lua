if redis.call('get', KEYS[1]) ~= ARGV[1] then
  return 13
end
redis.call('pexpire', KEYS[1], ARGV[2])
return 0
