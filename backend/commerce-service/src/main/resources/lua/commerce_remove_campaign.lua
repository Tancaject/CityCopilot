-- 原子目的：仅由当前修订所有者删除活动元数据，并在事务提交路径释放对应更新 fence。
-- KEYS[1]：活动修订号；KEYS[2]：活动元数据 Hash；KEYS[3]：活动更新 fence。
-- ARGV[1]：预期修订号；ARGV[2]：COMMIT 或 RECONCILE。
-- 返回值：1=删除成功；0=修订已变化或 COMMIT fence 不属于本修订；-1=RECONCILE 遇到活动更新 fence。
local currentRevision = tonumber(redis.call('get', KEYS[1]) or '0')
if currentRevision ~= tonumber(ARGV[1]) then return 0 end
-- 对账不能越过正在提交的管理变更；提交者也必须证明 fence 修订仍属于自己。
if ARGV[2] == 'RECONCILE' and redis.call('exists', KEYS[3]) == 1 then return -1 end
if ARGV[2] == 'COMMIT' then
  local fenceRevision = redis.call('get', KEYS[3])
  if fenceRevision and tonumber(fenceRevision) ~= tonumber(ARGV[1]) then return 0 end
end
redis.call('del', KEYS[2])
if ARGV[2] == 'COMMIT' then redis.call('del', KEYS[3]) end
return 1
