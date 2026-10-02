-- Global backlog sampling also serves admission capacity checks across routing generations.
CREATE INDEX idx_commerce_admission_status_age
    ON commerce_admission_ledger(status, accepted_at);

-- Bound completion sampling to a recent acceptance cohort, without scanning retained identities.
CREATE INDEX idx_commerce_admission_completion_window
    ON commerce_admission_ledger(accepted_at, status, routing_generation);
