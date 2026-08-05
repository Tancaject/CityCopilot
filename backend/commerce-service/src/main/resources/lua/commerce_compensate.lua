local priorResult = redis.call('get', KEYS[4])
if priorResult then
  return tonumber(priorResult)
end
local admittedOrderId = redis.call('get', KEYS[3])
if not admittedOrderId then
  -- Without the admission identity we cannot prove that stock was already
  -- restored. Force an evidence-based rebuild instead of assuming success.
  redis.call('set', KEYS[4], '-3')
  return -3
end
local expectedIdentity = ARGV[2] .. '|' .. ARGV[4]
if admittedOrderId ~= expectedIdentity
    and string.sub(admittedOrderId, 1, string.len(expectedIdentity) + 1)
        ~= expectedIdentity .. '|' then
  redis.call('set', KEYS[4], '-1')
  return -1
end
local stockValue = redis.call('get', KEYS[1])
if not stockValue or not tonumber(stockValue) then
  if ARGV[3] == 'true' then
    redis.call('sadd', KEYS[2], ARGV[1])
  else
    redis.call('srem', KEYS[2], ARGV[1])
  end
  redis.call('del', KEYS[3])
  redis.call('set', KEYS[4], '-2')
  return -2
end
redis.call('incr', KEYS[1])
if ARGV[3] == 'true' then
  redis.call('sadd', KEYS[2], ARGV[1])
else
  redis.call('srem', KEYS[2], ARGV[1])
end
redis.call('del', KEYS[3])
redis.call('set', KEYS[4], '1')
return 1
