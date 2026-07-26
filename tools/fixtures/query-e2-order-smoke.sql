SELECT COUNT(*) AS orders,
       COUNT(DISTINCT user_id) AS distinct_users,
       MIN(version) AS min_version,
       MAX(version) AS max_version
FROM coupon_order
WHERE voucher_id=100;

SELECT status, COUNT(*)
FROM seckill_reservation
WHERE voucher_id=100
GROUP BY status
ORDER BY status;

SELECT status, COUNT(*)
FROM outbox_event
WHERE aggregate_type='coupon_order'
  AND aggregate_id IN (
      SELECT CAST(id AS CHAR) FROM coupon_order WHERE voucher_id=100
  )
GROUP BY status
ORDER BY status;

SELECT status, COUNT(*)
FROM processed_event
WHERE consumer_group='citycopilot-event-audit-v1'
  AND aggregate_id IN (
      SELECT CAST(id AS CHAR) FROM coupon_order WHERE voucher_id=100
  )
GROUP BY status
ORDER BY status;

SELECT stock FROM seckill_coupon WHERE voucher_id=100;
