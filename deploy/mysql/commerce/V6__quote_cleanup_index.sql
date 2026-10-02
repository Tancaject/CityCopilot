-- Only unconsumed quotes participate in expiry cleanup; consumed history is not scanned.
CREATE INDEX idx_booking_quote_cleanup
    ON commerce_booking_quote(consumed_booking_id, expires_at, id);
