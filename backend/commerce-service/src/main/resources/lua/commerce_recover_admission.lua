-- 原子目的：Redis 故障切换后，依据持久账本中已证明 ACCEPTED 的身份补齐准入状态与 Stream 记录。
-- KEYS 与 commerce_seckill.lua 相同且必须共享一个 Cluster hash tag：
-- KEYS[1] 活动元数据（恢复不重验活动窗口）；KEYS[2] 库存；KEYS[3] 买家集合；KEYS[4] 分片 Stream；
-- KEYS[5] 幂等准入身份；KEYS[6] 库存维护 fence；KEYS[7] 在途集合。
-- ARGV[1] userId；ARGV[2] orderId；ARGV[3] voucherId；ARGV[4] idempotencyKey；ARGV[5] 请求指纹。
-- 返回值：0=恢复完成/已补发记录；3=原 Stream 记录仍有效并完成幂等重放；4=准入身份冲突；5=库存缺失或非法；
--         6=维护 fence 存在；12=库存不足以重建丢失准入；13=仅存买家标记、身份不完整；
--         14=原 Stream 记录字段与账本身份冲突。
if redis.call('exists', KEYS[6]) == 1 then return 6 end

local expected = ARGV[2] .. '|' .. ARGV[3] .. '|' .. ARGV[5]
local admitted = redis.call('get', KEYS[5])
if admitted then
  -- 已有准入身份表示库存曾被预扣；必须先核对 order|voucher|fingerprint，不能再次扣减。
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
        -- recordId 指向的消息若不属于同一账本身份，拒绝覆盖并转人工调查。
        return 14
      end
      redis.call('sadd', KEYS[3], ARGV[1])
      redis.call('sadd', KEYS[7], sourceId .. '|' .. ARGV[2])
      -- 同值写推进当前主连接的复制偏移，使调用方后续 WAIT 也覆盖本次幂等重放。
      redis.call('set', KEYS[5], admitted)
      return 3
    end
    redis.call('srem', KEYS[7], sourceId .. '|' .. ARGV[2])
  end

  -- 身份仍在但原消息丢失时只补发 Stream，不重复 DECR；随后更新身份中的新 recordId。
  local replacementId = redis.call('xadd', KEYS[4], '*',
    'orderId', ARGV[2], 'userId', ARGV[1], 'voucherId', ARGV[3],
    'idempotencyKey', ARGV[4], 'requestFingerprint', ARGV[5])
  redis.call('sadd', KEYS[3], ARGV[1])
  redis.call('set', KEYS[5], expected .. '|' .. replacementId)
  redis.call('sadd', KEYS[7], replacementId .. '|' .. ARGV[2])
  return 0
end

-- Lua 效果作为整体复制；身份缺失代表 Voucher 本地准入整体丢失。若只残留 buyer 标记，无法证明是否应扣库存，必须拒绝猜测。
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
