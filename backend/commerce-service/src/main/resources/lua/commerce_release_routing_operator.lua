-- 原子目的：尽力释放全局路由运维租约，但只删除仍属于当前操作者的令牌。
-- KEYS[1]：路由运维租约键；ARGV[1]：所有者令牌。
-- 返回值：始终为 0；调用方只关心安全释放，令牌不匹配时依靠原 TTL 失效。
if redis.call('get', KEYS[1]) == ARGV[1] then
  redis.call('del', KEYS[1])
end
return 0
