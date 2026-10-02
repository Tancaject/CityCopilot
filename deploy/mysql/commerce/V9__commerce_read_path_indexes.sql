-- Inventory restore polling filters by source and phase before reading the oldest batch.
CREATE INDEX idx_commerce_recovery_restore
    ON commerce_order_recovery(source_type, phase, created_at);

-- User booking pages sort by creation time, not by status or service start time.
-- InnoDB appends the primary key id, retaining the existing created_at/id order.
CREATE INDEX idx_booking_user_created
    ON commerce_booking(user_id, created_at);

-- The coupon wallet includes every status and returns the most recently claimed rows.
CREATE INDEX idx_user_booking_coupon_created
    ON commerce_user_booking_coupon(user_id, created_at);
