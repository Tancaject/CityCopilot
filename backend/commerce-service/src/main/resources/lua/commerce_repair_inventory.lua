-- 原子目的：在 Voucher repair fence 保护下，用权威数据库快照整体重建库存与买家集合。
-- KEYS[1]：repair fence；KEYS[2]：库存；KEYS[3]：买家集合。
-- ARGV[1]：预期 fence 令牌；ARGV[2]：目标库存；ARGV[3..n]：应保留的权威 userId 列表。
-- 返回值：非负目标库存=修复成功；-1=fence 所有权改变；-2=目标库存不是合法非负数字。
if redis.call('get', KEYS[1]) ~= ARGV[1] then
  return -1
end
local targetStock = tonumber(ARGV[2])
if not targetStock or targetStock < 0 then
  return -2
end
-- fence 校验后再整体替换买家集合和库存，旧修复任务不能覆盖新业务结果，准入也看不到半份快照。
redis.call('del', KEYS[3])
for index = 3, #ARGV do
  redis.call('sadd', KEYS[3], ARGV[index])
end
redis.call('set', KEYS[2], ARGV[2])
return targetStock
