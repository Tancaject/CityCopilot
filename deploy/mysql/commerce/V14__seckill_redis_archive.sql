-- Ended campaigns release their Redis state; reconciliation only scans campaigns that are still live or recently ended.
ALTER TABLE commerce_seckill_voucher
    ADD COLUMN redis_archived_at TIMESTAMP(3) NULL,
    ADD KEY idx_commerce_seckill_live (redis_archived_at, end_time);

-- Per-voucher consistency evidence and archive checks read orders and admissions by voucher.
ALTER TABLE commerce_order ADD KEY idx_commerce_order_voucher_status (voucher_id, status);
ALTER TABLE commerce_admission_ledger ADD KEY idx_commerce_admission_voucher_status (voucher_id, status);
