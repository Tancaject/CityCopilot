-- Tables no code reads or writes any more. The first six are left from the pre-split monolith: seckill, coupons and
-- the transactional outbox now belong to Commerce. Only development databases taken over by baseline still have
-- them; databases created by Flyway have just user_checkin and user_profile (check-in and profile were removed).
DROP TABLE IF EXISTS coupon_order, seckill_reservation, seckill_coupon, coupon, outbox_fence, outbox_event,
    user_checkin, user_profile;
