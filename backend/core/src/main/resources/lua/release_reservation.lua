local reservationKey = KEYS[1]
local stockKey = KEYS[2]
local buyerKey = KEYS[3]
local userId = ARGV[1]
local current = redis.call('hget', reservationKey, 'status')
if current == 'RELEASED' then
    return 1
end
if current == 'ORDER_CREATED' then
    return -1
end
if current == false then
    return 0
end
redis.call('incrby', stockKey, 1)
redis.call('srem', buyerKey, userId)
redis.call('hset', reservationKey, 'status', 'RELEASED')
redis.call('expire', reservationKey, 604800)
return 1
