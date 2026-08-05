local currentRevision = tonumber(redis.call('get', KEYS[1]) or '0')
if currentRevision ~= tonumber(ARGV[1]) then return 0 end
if ARGV[8] == 'RECONCILE' and redis.call('exists', KEYS[3]) == 1 then return -1 end
if ARGV[8] == 'COMMIT' then
  local fenceRevision = redis.call('get', KEYS[3])
  if fenceRevision and tonumber(fenceRevision) ~= tonumber(ARGV[1]) then return 0 end
end
redis.call('del', KEYS[2])
redis.call('hset', KEYS[2],
  'voucherId', ARGV[2],
  'type', ARGV[3],
  'status', ARGV[4],
  'beginEpochMillis', ARGV[5],
  'endEpochMillis', ARGV[6],
  'version', ARGV[7])
if ARGV[8] == 'COMMIT' then redis.call('del', KEYS[3]) end
return 1
