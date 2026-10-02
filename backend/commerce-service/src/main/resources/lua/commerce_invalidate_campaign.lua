-- 原子目的：在 MySQL 活动变更前关闭 Redis 准入，递增修订号并建立限时更新 fence。
-- KEYS[1]：活动修订号；KEYS[2]：活动元数据 Hash；KEYS[3]：活动更新 fence。
-- ARGV[1]：更新 fence TTL（毫秒）。
-- 返回值：本次新修订号，供事务提交后的发布/删除操作做所有权校验。
local revision = redis.call('incr', KEYS[1])
redis.call('psetex', KEYS[3], ARGV[1], tostring(revision))
-- 删除旧元数据使变更窗口失败关闭，准入不会继续读取过期活动配置。
redis.call('del', KEYS[2])
return revision
