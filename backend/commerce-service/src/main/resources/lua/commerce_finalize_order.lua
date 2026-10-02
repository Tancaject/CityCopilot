-- 原子目的：在数据库 Recovery 已进入终态后，同时清理在途标记、补偿 marker，并 ACK+删除 Stream 消息。
-- KEYS[1]：Voucher 在途集合；KEYS[2]：订单分片 Stream；KEYS[3]：该来源的补偿 marker。
-- ARGV[1]：在途成员 recordId|orderId；ARGV[2]：Stream recordId；ARGV[3]：消费组名。
-- 返回值：XACK 的确认数量，通常 1；重复终态化时为 0。XDEL 仍执行，使脚本可安全重放。
local member = ARGV[1]
local sourceId = ARGV[2]
local group = ARGV[3]
redis.call('srem', KEYS[1], member)
redis.call('del', KEYS[3])
-- ACK 与 XDEL 放在同一 Lua 中，避免 ACK 成功后进程崩溃而留下永不再消费的历史记录。
local acknowledged = redis.call('xack', KEYS[2], group, sourceId)
redis.call('xdel', KEYS[2], sourceId)
return acknowledged
