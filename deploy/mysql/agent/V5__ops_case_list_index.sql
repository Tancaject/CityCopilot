-- Ops case listing orders by created_at DESC with a bounded LIMIT; status indexes cannot serve that prefix.
ALTER TABLE ops_case ADD INDEX idx_ops_case_created (created_at);
