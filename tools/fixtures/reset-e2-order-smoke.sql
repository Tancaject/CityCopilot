DELETE FROM seckill_reservation WHERE voucher_id=100;
DELETE FROM coupon_order WHERE voucher_id=100;
UPDATE seckill_coupon
SET stock=10,
    begin_time=DATE_SUB(NOW(), INTERVAL 1 DAY),
    end_time=DATE_ADD(NOW(), INTERVAL 30 DAY)
WHERE voucher_id=100;
