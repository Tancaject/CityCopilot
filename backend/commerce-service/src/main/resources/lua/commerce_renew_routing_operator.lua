-- 原子目的：仅由仍持有令牌的路由操作者延长租约，防止过期进程续租新所有者的锁。
-- KEYS[1]：路由运维租约键；ARGV[1]：预期所有者令牌；ARGV[2]：新 TTL（毫秒）。
-- 返回值：0=续租成功；13=租约已过期、缺失或所有权改变。
if redis.call('get', KEYS[1]) ~= ARGV[1] then
  return 13
end
redis.call('pexpire', KEYS[1], ARGV[2])
return 0
