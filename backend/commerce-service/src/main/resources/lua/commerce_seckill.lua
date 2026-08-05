-- KEYS are campaign meta, stock, buyers, shard stream, idempotency,
-- maintenance fence, in-flight set and routing gate. They must share one
-- Cluster hash tag.
if redis.call('get', KEYS[8]) ~= ARGV[6] then return 11 end
if redis.call('exists', KEYS[6]) == 1 then return 6 end

local metadata = redis.call(
  'hmget', KEYS[1], 'type', 'status', 'beginEpochMillis', 'endEpochMillis')
if not metadata[1] or not metadata[2] or not metadata[3] or not metadata[4] then
  return 7
end
local campaignType = tonumber(metadata[1])
local campaignStatus = tonumber(metadata[2])
local beginEpochMillis = tonumber(metadata[3])
local endEpochMillis = tonumber(metadata[4])
if not campaignType or not campaignStatus or not beginEpochMillis or not endEpochMillis then
  return 7
end
if campaignType ~= 1 or campaignStatus ~= 1 then return 8 end

local serverTime = redis.call('time')
local nowEpochMillis = tonumber(serverTime[1]) * 1000 + math.floor(tonumber(serverTime[2]) / 1000)
if nowEpochMillis < beginEpochMillis then return 9 end
if nowEpochMillis > endEpochMillis then return 10 end

local admittedOrderId = redis.call('get', KEYS[5])
if admittedOrderId then
  local firstSeparator = string.find(admittedOrderId, '|', 1, true)
  if firstSeparator then
    local storedIdentity = string.sub(admittedOrderId, firstSeparator + 1)
    local expectedIdentity = ARGV[3] .. '|' .. ARGV[5]
    if storedIdentity == expectedIdentity or
       string.sub(storedIdentity, 1, string.len(expectedIdentity) + 1) == expectedIdentity .. '|' then
      -- Advance this primary connection's replication offset before the
      -- caller issues WAIT, including on an idempotent replay.
      redis.call('set', KEYS[5], admittedOrderId)
      return 3
    end
  end
  return 4
end
local stockValue = redis.call('get', KEYS[2])
if not stockValue then return 5 end
local stock = tonumber(stockValue)
if not stock then return 5 end
if stock <= 0 then return 1 end
if redis.call('sismember', KEYS[3], ARGV[1]) == 1 then return 2 end
redis.call('decr', KEYS[2])
redis.call('sadd', KEYS[3], ARGV[1])
local sourceId = redis.call('xadd', KEYS[4], '*',
  'orderId', ARGV[2], 'userId', ARGV[1], 'voucherId', ARGV[3],
  'idempotencyKey', ARGV[4], 'requestFingerprint', ARGV[5])
redis.call('set', KEYS[5],
  ARGV[2] .. '|' .. ARGV[3] .. '|' .. ARGV[5] .. '|' .. sourceId)
redis.call('sadd', KEYS[7], sourceId .. '|' .. ARGV[2])
return 0
