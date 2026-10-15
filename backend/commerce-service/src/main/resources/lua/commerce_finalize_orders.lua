-- 原子目的：同一 Voucher 的一批订单在数据库 Recovery 均已终态后，逐条清理在途标记、补偿 marker，并 ACK+删除 Stream 消息。
-- KEYS[1]：Voucher 在途集合；KEYS[2]：订单分片 Stream；KEYS[3..N+2]：各来源的补偿 marker。
-- ARGV[1]：消费组名；ARGV[2..]：按 KEYS 顺序成对排列的在途成员 recordId|orderId 与 Stream recordId。
-- 返回值：本次实际 XACK 的总数。其他 Worker 已收尾的记录返回 0，属于正常重放，调用方不能要求等于 N。
local group = ARGV[1]
local acknowledged = 0
for i = 3, #KEYS do
  local member = ARGV[(i - 3) * 2 + 2]
  local recordId = ARGV[(i - 3) * 2 + 3]
  redis.call('srem', KEYS[1], member)
  redis.call('del', KEYS[i])
  acknowledged = acknowledged + redis.call('xack', KEYS[2], group, recordId)
  redis.call('xdel', KEYS[2], recordId)
end
return acknowledged
