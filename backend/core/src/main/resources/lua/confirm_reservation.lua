local reservationKey = KEYS[1]
local current = redis.call('hget', reservationKey, 'status')
if current == false then
    return 0
end
if current == 'ORDER_CREATED' then
    return 1
end
if current == 'RELEASED' then
    return -1
end
redis.call('hset', reservationKey, 'status', 'ORDER_CREATED')
redis.call('expire', reservationKey, 604800)
return 1
