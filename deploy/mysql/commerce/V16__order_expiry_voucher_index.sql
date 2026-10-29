-- Order expiry skips vouchers under inventory maintenance with voucher_id NOT IN (...). Appending voucher_id after the
-- primary key keeps the (pay_deadline,id) scan order of ORDER BY pay_deadline,id LIMIT 1, while letting MySQL filter
-- skipped vouchers inside the index instead of reading every skipped order row.
ALTER TABLE commerce_order
    DROP INDEX idx_commerce_order_expiry,
    ADD INDEX idx_commerce_order_expiry (status, pay_deadline, id, voucher_id);
