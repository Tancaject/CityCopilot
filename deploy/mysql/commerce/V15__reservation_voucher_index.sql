-- Stock reconciliation reads each active campaign's reservations by voucher and status
-- (buyer comparison and unresolved reservations); without this index both queries scan the whole table.
CREATE INDEX idx_commerce_reservation_voucher_status
    ON commerce_reservation(voucher_id, status);
