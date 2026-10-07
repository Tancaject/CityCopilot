-- 原子目的：完成秒杀准入的维护/活动/时间窗/库存/一人一单/幂等检查，并一次性预扣库存、标记买家、写 Stream 与在途集合。
-- 以下键必须共享同一个 Voucher Cluster hash tag：
-- KEYS[1] 活动元数据 Hash；KEYS[2] 库存；KEYS[3] 买家集合；KEYS[4] 分片订单 Stream；
-- KEYS[5] 用户+幂等键的准入身份；KEYS[6] 库存维护 fence；KEYS[7] 在途集合。
-- ARGV[1] userId；ARGV[2] orderId；ARGV[3] voucherId；ARGV[4] idempotencyKey；ARGV[5] 请求指纹。
-- 返回值：0=新准入成功；1=库存不足；2=用户已购买；3=同身份幂等重放；4=幂等键冲突；5=库存缺失/非法；
--         6=库存维护中；7=活动元数据缺失/非法；8=非可用秒杀活动；9=尚未开始；10=已结束。
-- 先验证维护 fence，再读取或修改业务键，避免修复期间产生新的准入。
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

-- 使用 Redis TIME 判断活动窗口，消除多个 Commerce 实例本地时钟偏差。
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
      -- 同值写推进当前主连接复制偏移，使调用方后续 WAIT 也覆盖幂等重放。
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
-- 预扣、买家标记、入队、幂等身份和在途标记同一原子提交；任何观察者都不会看到半准入状态。
redis.call('decr', KEYS[2])
redis.call('sadd', KEYS[3], ARGV[1])
local sourceId = redis.call('xadd', KEYS[4], '*',
  'orderId', ARGV[2], 'userId', ARGV[1], 'voucherId', ARGV[3],
  'idempotencyKey', ARGV[4], 'requestFingerprint', ARGV[5])
redis.call('set', KEYS[5],
  ARGV[2] .. '|' .. ARGV[3] .. '|' .. ARGV[5] .. '|' .. sourceId)
redis.call('sadd', KEYS[7], sourceId .. '|' .. ARGV[2])
return 0
