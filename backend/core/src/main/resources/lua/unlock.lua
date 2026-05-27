-- 释放锁的 Lua 脚本
-- KEYS[1]: 锁的 key
-- ARGV[1]: 当前线程的标识（UUID + 线程ID）

-- 判断锁是否是自己的
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 是自己的锁，删除
    return redis.call('del', KEYS[1])
end
-- 不是自己的锁，不删除
return 0
