ALTER TABLE commerce_order
    ADD COLUMN pay_deadline TIMESTAMP(3) NULL,
    ADD COLUMN close_reason VARCHAR(32) NULL,
    ADD COLUMN active_voucher_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status<>4 THEN voucher_id ELSE NULL END) STORED,
    DROP INDEX uk_commerce_order_user_voucher,
    ADD UNIQUE KEY uk_commerce_order_user_voucher (user_id,active_voucher_id),
    ADD KEY idx_commerce_order_expiry (status,pay_deadline);

UPDATE commerce_order SET pay_deadline=create_time+INTERVAL 15 MINUTE;
ALTER TABLE commerce_order
    MODIFY COLUMN pay_deadline TIMESTAMP(3) NOT NULL
        DEFAULT (CURRENT_TIMESTAMP(3)+INTERVAL 15 MINUTE);

UPDATE commerce_reservation r JOIN commerce_order o ON o.id=r.order_id
SET r.status='CANCELLED',r.version=r.version+1
WHERE o.status=4 AND r.status='ORDER_CREATED';

ALTER TABLE commerce_reservation
    ADD COLUMN active_voucher_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status IN ('RESERVED','ORDER_CREATED') THEN voucher_id ELSE NULL END) STORED,
    DROP INDEX uk_commerce_reservation_user_voucher,
    ADD UNIQUE KEY uk_commerce_reservation_user_voucher (user_id,active_voucher_id);
