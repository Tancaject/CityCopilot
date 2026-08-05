if redis.call('get', KEYS[1]) ~= ARGV[1] then
  return -1
end
local targetStock = tonumber(ARGV[2])
if not targetStock or targetStock < 0 then
  return -2
end
redis.call('del', KEYS[3])
for index = 3, #ARGV do
  redis.call('sadd', KEYS[3], ARGV[index])
end
redis.call('set', KEYS[2], ARGV[2])
return targetStock
