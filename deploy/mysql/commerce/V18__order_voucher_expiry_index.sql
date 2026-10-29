-- Order expiry locks one voucher's due unpaid orders in a batch:
--   WHERE voucher_id=? AND status=1 AND pay_deadline<=now ORDER BY pay_deadline,id LIMIT ? FOR UPDATE SKIP LOCKED.
-- With only (voucher_id, status) the scan reads, and under REPEATABLE READ keeps locked, every unpaid order of the
-- voucher, blocking payments of orders that are not due yet. Appending pay_deadline limits the scan and the locks
-- to due orders in (pay_deadline, id) order; the existing (voucher_id, status) lookups keep using the same prefix.
ALTER TABLE commerce_order
    DROP INDEX idx_commerce_order_voucher_status,
    ADD INDEX idx_commerce_order_voucher_status (voucher_id, status, pay_deadline);
