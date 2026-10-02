-- 原子目的：终态化一条分片 Stream 消息，同时完成消费组 ACK 与记录删除，避免只 ACK 未删除留下无意义历史。
-- KEYS[1]：订单所在的 Redis Stream 键。
-- ARGV[1]：消费组名；ARGV[2]：待终态化的 Stream recordId。
-- 返回值：XACK 的确认数量，单条记录通常为 1；已确认或不在 PEL 时为 0，记录仍会执行 XDEL。
local acknowledged = redis.call('xack', KEYS[1], ARGV[1], ARGV[2])
redis.call('xdel', KEYS[1], ARGV[2])
return acknowledged
