CREATE TABLE commerce_payment (
    payment_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    amount INT NOT NULL,
    pay_type TINYINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    channel_transaction_id VARCHAR(96) NULL,
    callback_result VARCHAR(24) NULL,
    callback_due_at TIMESTAMP(3) NULL,
    reconciliation_issue VARCHAR(64) NULL,
    reconciled_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (payment_id),
    UNIQUE KEY uk_commerce_payment_order (order_id),
    UNIQUE KEY uk_commerce_payment_channel (channel_transaction_id),
    KEY idx_commerce_payment_callback (callback_result,callback_due_at),
    KEY idx_commerce_payment_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve the already persisted simulated payments; these rows never schedule a new charge.
INSERT INTO commerce_payment(payment_id,order_id,amount,pay_type,status,channel_transaction_id,callback_result,created_at)
SELECT CONCAT('legacy:',id),id,pay_value_snapshot,COALESCE(pay_type,1),
       CASE status WHEN 5 THEN 'REFUND_PENDING' WHEN 6 THEN 'REFUNDED' ELSE 'PAID' END,
       CONCAT('legacy:',id),'PAID',COALESCE(pay_time,create_time)
FROM commerce_order WHERE status IN (2,3,5,6);
