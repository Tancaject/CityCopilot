-- 原子目的：幂等撤销一次已准入但未落单的秒杀，恢复库存、修正买家集合并清理准入身份。
-- KEYS[1]：Voucher 库存；KEYS[2]：已准入用户集合；KEYS[3]：本次幂等准入身份；KEYS[4]：补偿结果 marker。
-- ARGV[1]：userId；ARGV[2]：orderId；ARGV[3]：是否因其他有效购买而保留买家标记；ARGV[4]：voucherId。
-- 返回值：1=补偿成功或已成功补偿；-1=准入身份与订单/Voucher 不符；-2=库存缺失或非数字，已清理身份但需修复；
--         -3=准入身份缺失且无 marker，无法证明是否应返库存，必须基于证据重建。
local priorResult = redis.call('get', KEYS[4])
if priorResult then
  -- marker 保存首轮结果，重试直接复用，绝不重复 INCR 库存。
  return tonumber(priorResult)
end
local admittedOrderId = redis.call('get', KEYS[3])
if not admittedOrderId then
  -- 没有准入身份就无法证明库存是否已预扣或已恢复；拒绝猜测，转入证据修复。
  redis.call('set', KEYS[4], '-3')
  return -3
end
local expectedIdentity = ARGV[2] .. '|' .. ARGV[4]
if admittedOrderId ~= expectedIdentity
    and string.sub(admittedOrderId, 1, string.len(expectedIdentity) + 1)
        ~= expectedIdentity .. '|' then
  -- 先校验 orderId|voucherId 前缀，防止过期恢复任务补偿另一笔准入。
  redis.call('set', KEYS[4], '-1')
  return -1
end
local stockValue = redis.call('get', KEYS[1])
if not stockValue or not tonumber(stockValue) then
  -- 库存不可信时不做 INCR，但仍按数据库事实修正买家标记并移除已确认的准入身份。
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
-- 若同一用户仍有其他有效购买，必须保留 buyers 成员，避免补偿破坏“一人一单”约束。
if ARGV[3] == 'true' then
  redis.call('sadd', KEYS[2], ARGV[1])
else
  redis.call('srem', KEYS[2], ARGV[1])
end
redis.call('del', KEYS[3])
redis.call('set', KEYS[4], '1')
return 1
