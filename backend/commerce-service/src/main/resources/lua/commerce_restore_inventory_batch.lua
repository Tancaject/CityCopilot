-- 原子目的：同一张券一批订单取消后，在同一个库存 fence 下幂等返还 Redis 库存，逐单写恢复 marker。
-- 与 commerce_restore_inventory.lua 逐单语义相同：marker 已存在的单不再返还；返还与 marker 同一原子提交后释放 fence。
-- KEYS[1]：Voucher 库存 fence；KEYS[2]：库存；KEYS[3]：买家集合；KEYS[4..]：每单按恢复来源隔离的幂等 marker。
-- ARGV[1]：预期 fence 令牌；第 i 单（从 1 起）的买家 ID 在 ARGV[2i]，是否释放购买资格在 ARGV[2i+1]。
-- 返回值：>=0 本次返还的单数（0 表示全部此前已返还）；-1 仍有未返还的单但 fence 所有权已改变；
--         -2 库存缺失或非数字，未增加库存。
local pending = {}
for k = 4, #KEYS do
  if redis.call('exists', KEYS[k]) == 0 then pending[#pending + 1] = k end
end
if #pending == 0 then
  -- 全部此前已返还（例如首轮结果丢失后重试）；仍持有本次 fence 时顺手释放，不让它拖到 TTL 才解除。
  if redis.call('get', KEYS[1]) == ARGV[1] then redis.call('del', KEYS[1]) end
  return 0
end
if redis.call('get', KEYS[1]) ~= ARGV[1] then
  return -1
end
local stockValue = redis.call('get', KEYS[2])
if not stockValue or not tonumber(stockValue) then
  -- 库存不可证明时释放本次 fence 并转证据修复，绝不凭默认值返库存。
  redis.call('del', KEYS[1])
  return -2
end
redis.call('incrby', KEYS[2], #pending)
for _, k in ipairs(pending) do
  local item = k - 3
  if ARGV[item * 2 + 1] == 'true' then redis.call('srem', KEYS[3], ARGV[item * 2]) end
  redis.call('set', KEYS[k], '1')
end
redis.call('del', KEYS[1])
redis.call('publish', 'commerce:inventory:restored', KEYS[2])
return #pending
