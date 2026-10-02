ALTER TABLE ops_case
    ADD INDEX idx_ops_case_status_created (status, created_at),
    ADD INDEX idx_ops_case_status_resolved (status, resolved_at, created_at);
