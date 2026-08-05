local currentRevision = tonumber(redis.call('get', KEYS[1]) or '0')
if currentRevision ~= tonumber(ARGV[1]) then return 0 end
if ARGV[2] == 'RECONCILE' and redis.call('exists', KEYS[3]) == 1 then return -1 end
if ARGV[2] == 'COMMIT' then
  local fenceRevision = redis.call('get', KEYS[3])
  if fenceRevision and tonumber(fenceRevision) ~= tonumber(ARGV[1]) then return 0 end
end
redis.call('del', KEYS[2])
if ARGV[2] == 'COMMIT' then redis.call('del', KEYS[3]) end
return 1
