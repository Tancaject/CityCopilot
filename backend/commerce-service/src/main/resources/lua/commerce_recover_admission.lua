-- Re-establish a ledger-proven ACCEPTED admission after a Redis failover.
-- KEYS match commerce_seckill.lua and share one Cluster hash tag.
-- ARGV are userId, orderId, voucherId, idempotencyKey, fingerprint,
-- ACTIVE gate value and DRAINING gate value.
local gate = redis.call('get', KEYS[8])
if gate ~= ARGV[6] and gate ~= ARGV[7] then return 11 end
if redis.call('exists', KEYS[6]) == 1 then return 6 end

local expected = ARGV[2] .. '|' .. ARGV[3] .. '|' .. ARGV[5]
local admitted = redis.call('get', KEYS[5])
if admitted then
  if admitted ~= expected and
     string.sub(admitted, 1, string.len(expected) + 1) ~= expected .. '|' then
    return 4
  end

  local sourceId = nil
  if string.len(admitted) > string.len(expected) then
    sourceId = string.sub(admitted, string.len(expected) + 2)
  end
  if sourceId and sourceId ~= '' then
    local records = redis.call('xrange', KEYS[4], sourceId, sourceId, 'COUNT', 1)
    if records and #records == 1 then
      local fields = records[1][2]
      local payload = {}
      for index = 1, #fields, 2 do
        payload[fields[index]] = fields[index + 1]
      end
      if payload['orderId'] ~= ARGV[2] or payload['userId'] ~= ARGV[1] or
         payload['voucherId'] ~= ARGV[3] or
         payload['idempotencyKey'] ~= ARGV[4] or
         payload['requestFingerprint'] ~= ARGV[5] then
        return 14
      end
      redis.call('sadd', KEYS[3], ARGV[1])
      redis.call('sadd', KEYS[7], sourceId .. '|' .. ARGV[2])
      -- Same-value write makes the subsequent WAIT cover this replay.
      redis.call('set', KEYS[5], admitted)
      return 3
    end
    redis.call('srem', KEYS[7], sourceId .. '|' .. ARGV[2])
  end

  local replacementId = redis.call('xadd', KEYS[4], '*',
    'orderId', ARGV[2], 'userId', ARGV[1], 'voucherId', ARGV[3],
    'idempotencyKey', ARGV[4], 'requestFingerprint', ARGV[5])
  redis.call('sadd', KEYS[3], ARGV[1])
  redis.call('set', KEYS[5], expected .. '|' .. replacementId)
  redis.call('sadd', KEYS[7], replacementId .. '|' .. ARGV[2])
  return 0
end

-- Lua effects replicate atomically. For the failover boundary this means a
-- missing admission identity represents a lost voucher-local admission. If a
-- buyer marker survived without the identity, refuse to guess and alert.
local stockValue = redis.call('get', KEYS[2])
if not stockValue then return 5 end
local stock = tonumber(stockValue)
if not stock then return 5 end
if stock <= 0 then return 12 end
if redis.call('sismember', KEYS[3], ARGV[1]) == 1 then return 13 end

redis.call('decr', KEYS[2])
redis.call('sadd', KEYS[3], ARGV[1])
local sourceId = redis.call('xadd', KEYS[4], '*',
  'orderId', ARGV[2], 'userId', ARGV[1], 'voucherId', ARGV[3],
  'idempotencyKey', ARGV[4], 'requestFingerprint', ARGV[5])
redis.call('set', KEYS[5], expected .. '|' .. sourceId)
redis.call('sadd', KEYS[7], sourceId .. '|' .. ARGV[2])
return 0
