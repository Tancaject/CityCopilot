-- 原子目的：仅由当前令牌所有者释放 Voucher 库存维护/修复 fence，避免过期任务删除新所有者的保护。
-- KEYS[1]：fence 键；ARGV[1]：预期所有者令牌。
-- 返回值：1=令牌匹配并删除；0=fence 不存在或已更换所有者。
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('del', KEYS[1])
end
return 0
