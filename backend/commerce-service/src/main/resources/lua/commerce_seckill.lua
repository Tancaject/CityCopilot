if redis.call('exists', KEYS[5]) == 1 then return 6 end
local admittedOrderId = redis.call('get', KEYS[4])
if admittedOrderId then
  if admittedOrderId == ARGV[2] .. '|' .. ARGV[3] then return 3 end
  return 4
end
local stockValue = redis.call('get', KEYS[1])
if not stockValue then return 5 end
local stock = tonumber(stockValue)
if not stock then return 5 end
if stock <= 0 then return 1 end
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then return 2 end
redis.call('decr', KEYS[1])
redis.call('sadd', KEYS[2], ARGV[1])
redis.call('set', KEYS[4], ARGV[2] .. '|' .. ARGV[3], 'EX', 604800)
local sourceId = redis.call('xadd', KEYS[3], '*',
  'orderId', ARGV[2], 'userId', ARGV[1], 'voucherId', ARGV[3],
  'idempotencyKey', ARGV[4])
redis.call('sadd', KEYS[6], sourceId .. '|' .. ARGV[2])
return 0
