local marker = KEYS[3]
if redis.call('exists', marker) == 1 then
  return 2
end
if redis.call('get', KEYS[1]) ~= ARGV[1] then
  return 0
end
local stockValue = redis.call('get', KEYS[2])
if not stockValue or not tonumber(stockValue) then
  redis.call('del', KEYS[1])
  return -2
end
redis.call('incr', KEYS[2])
redis.call('set', marker, '1')
redis.call('del', KEYS[1])
return 1
