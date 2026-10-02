-- 原子目的：订单取消/退款后，在库存 fence 下幂等返还一次 Redis 库存并记录恢复 marker。
-- KEYS[1]：Voucher 库存 fence；KEYS[2]：库存；KEYS[3]：按恢复来源隔离的幂等 marker。
-- ARGV[1]：预期 fence 令牌。
-- 返回值：1=本次成功返库存；2=marker 已存在、此前已恢复；0=fence 所有权改变；-2=库存缺失或非数字，未增加库存。
local marker = KEYS[3]
if redis.call('exists', marker) == 1 then
  -- marker 优先判定，使调用方在首轮结果丢失后可安全重试而不重复 INCR。
  return 2
end
if redis.call('get', KEYS[1]) ~= ARGV[1] then
  return 0
end
local stockValue = redis.call('get', KEYS[2])
if not stockValue or not tonumber(stockValue) then
  -- 库存不可证明时释放本次 fence 并转证据修复，绝不凭默认值返库存。
  redis.call('del', KEYS[1])
  return -2
end
redis.call('incr', KEYS[2])
redis.call('set', marker, '1')
-- 返库存与 marker 同一原子提交后即可释放本次 fence。
redis.call('del', KEYS[1])
return 1
