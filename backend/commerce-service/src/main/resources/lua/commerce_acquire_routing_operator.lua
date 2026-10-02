-- 原子目的：以带 TTL 的 SET NX 取得全局路由运维互斥租约，防止两个迁移操作者同时改控制面。
-- KEYS[1]：路由运维租约键。
-- ARGV[1]：所有者令牌；ARGV[2]：租约 TTL（毫秒）。
-- 返回值：0=取得租约；12=已有未过期所有者。
local acquired = redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])
if acquired then
  return 0
end
return 12
