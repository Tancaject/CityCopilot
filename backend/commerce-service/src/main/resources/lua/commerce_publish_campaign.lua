-- 原子目的：仅由当前修订所有者发布完整活动元数据，防止旧事务或后台对账重新开放过期准入。
-- KEYS[1]：活动修订号；KEYS[2]：活动元数据 Hash；KEYS[3]：活动更新 fence。
-- ARGV[1]：预期修订号；ARGV[2]：voucherId；ARGV[3]：类型；ARGV[4]：状态；
-- ARGV[5]：开始时间毫秒；ARGV[6]：结束时间毫秒；ARGV[7]：数据库组合版本；ARGV[8]：COMMIT 或 RECONCILE。
-- 返回值：1=发布成功；0=修订已变化或 COMMIT fence 不属于本修订；-1=RECONCILE 遇到活动更新 fence。
local currentRevision = tonumber(redis.call('get', KEYS[1]) or '0')
if currentRevision ~= tonumber(ARGV[1]) then return 0 end
-- 对账不得覆盖正在提交的管理事务；COMMIT 也必须证明 fence 仍属于自己的修订。
if ARGV[8] == 'RECONCILE' and redis.call('exists', KEYS[3]) == 1 then return -1 end
if ARGV[8] == 'COMMIT' then
  local fenceRevision = redis.call('get', KEYS[3])
  if fenceRevision and tonumber(fenceRevision) ~= tonumber(ARGV[1]) then return 0 end
end
redis.call('del', KEYS[2])
-- 先删后完整 HSET 仍处在一个 Lua 原子操作内，其他请求看不到半份活动字段。
redis.call('hset', KEYS[2],
  'voucherId', ARGV[2],
  'type', ARGV[3],
  'status', ARGV[4],
  'beginEpochMillis', ARGV[5],
  'endEpochMillis', ARGV[6],
  'version', ARGV[7])
if ARGV[8] == 'COMMIT' then redis.call('del', KEYS[3]) end
return 1
