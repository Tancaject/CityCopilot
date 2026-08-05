-- Enables bounded multi-replica reclaim scans for expired Outbox leases.
-- Safe to re-run on MySQL 8.

SET @commerce_outbox_claim_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'commerce_outbox_event'
    AND INDEX_NAME = 'idx_commerce_outbox_claim'
);

SET @commerce_outbox_claim_index_ddl = IF(
  @commerce_outbox_claim_index_exists = 0,
  'CREATE INDEX idx_commerce_outbox_claim ON commerce_outbox_event(status, claimed_until)',
  'SELECT 1'
);

PREPARE commerce_outbox_claim_index_statement FROM @commerce_outbox_claim_index_ddl;
EXECUTE commerce_outbox_claim_index_statement;
DEALLOCATE PREPARE commerce_outbox_claim_index_statement;
