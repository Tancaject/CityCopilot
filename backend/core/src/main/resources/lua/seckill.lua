---
--- Created by Orion
--- DateTime: 2026/3/14 22:08
---优惠券id
local voucherId=ARGV[1]
---用户id
local userId = ARGV[2]
---订单id
local orderId = ARGV[3]

---库存key
local stockKey = 'coupon:stock:' .. voucherId
---订单key
local orderKey = 'coupon:buyer:' .. voucherId
local reservationKey = 'coupon:reservation:' .. orderId

---判断库存是否充足
local stock = redis.call('get', stockKey)
if (stock == false or tonumber(stock) <= 0) then
    ---如果库存不存在或不足
    return 1
end
---判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    ---存在说明已经下过单
    return 2
end
---扣除库存
redis.call('incrby', stockKey, -1)
---写入用户id
redis.call('sadd', orderKey, userId)
redis.call('hset', reservationKey,
        'orderId', orderId,
        'voucherId', voucherId,
        'userId', userId,
        'status', 'RESERVED')
redis.call('expire', reservationKey, 604800)
---发送消息到消息队列中
redis.call('xadd','stream.coupon-orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
