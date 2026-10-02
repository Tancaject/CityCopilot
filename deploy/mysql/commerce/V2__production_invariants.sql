CREATE TEMPORARY TABLE commerce_identity_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_commerce_identity_guard CHECK (valid = 1)
);
INSERT INTO commerce_identity_guard(valid)
SELECT ((SELECT CONCAT_WS('|',
  (SELECT COUNT(*)
   FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_order'
     AND COLUMN_NAME='idempotency_key'
     AND DATA_TYPE='varchar'
     AND CHARACTER_MAXIMUM_LENGTH=128
     AND CHARACTER_SET_NAME='utf8mb4'
     AND IS_NULLABLE='NO'),
  (SELECT COUNT(*)
   FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_order_recovery'
     AND COLUMN_NAME='idempotency_key'
     AND DATA_TYPE='varchar'
     AND CHARACTER_MAXIMUM_LENGTH=128
     AND CHARACTER_SET_NAME='utf8mb4'
     AND IS_NULLABLE='YES'),
  (SELECT COALESCE(CONCAT(MIN(NON_UNIQUE),':',
          GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),'')
   FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_order'
     AND INDEX_NAME='uk_commerce_order_user_idempotency'),
  (SELECT COALESCE(CONCAT(MIN(NON_UNIQUE),':',
          GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),'')
   FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_order_recovery'
     AND INDEX_NAME='PRIMARY')
)) = '1|1|0:user_id,idempotency_key|0:source_id');
DROP TEMPORARY TABLE commerce_identity_guard;
CREATE TEMPORARY TABLE commerce_outbox_duplicate_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_commerce_outbox_duplicate_guard CHECK (valid = 1)
);
INSERT INTO commerce_outbox_duplicate_guard(valid)
SELECT ((SELECT COUNT(*) FROM (
  SELECT aggregate_type,aggregate_id,event_type,aggregate_version
  FROM commerce_outbox_event
  GROUP BY aggregate_type,aggregate_id,event_type,aggregate_version
  HAVING COUNT(*) > 1
) duplicate_aggregates) = '0');
DROP TEMPORARY TABLE commerce_outbox_duplicate_guard;
CREATE TEMPORARY TABLE commerce_invariant_shape_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_commerce_invariant_shape_guard CHECK (valid = 1)
);
INSERT INTO commerce_invariant_shape_guard(valid)
SELECT (SELECT COALESCE(
  CONCAT(MIN(NON_UNIQUE),':',GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),
  ''
)
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='commerce_outbox_event'
  AND INDEX_NAME='uk_commerce_outbox_aggregate_version') IN ('','0:aggregate_type,aggregate_id,event_type,aggregate_version');
INSERT INTO commerce_invariant_shape_guard(valid)
SELECT COALESCE(CONCAT(MIN(NON_UNIQUE),':',GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),'')
       IN ('','1:status,claimed_until')
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_outbox_event' AND INDEX_NAME='idx_commerce_outbox_claim';
INSERT INTO commerce_invariant_shape_guard(valid)
SELECT NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_id_segment'
) OR (
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_id_segment' AND ENGINE='InnoDB')=1
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_id_segment'
           AND ((COLUMN_NAME='namespace' AND DATA_TYPE='varchar' AND CHARACTER_MAXIMUM_LENGTH=32 AND IS_NULLABLE='NO')
             OR (COLUMN_NAME='next_value' AND DATA_TYPE='bigint' AND IS_NULLABLE='NO')
             OR (COLUMN_NAME='update_time' AND DATA_TYPE='timestamp' AND IS_NULLABLE='NO')))=3
    AND (SELECT COALESCE(CONCAT(MIN(NON_UNIQUE),':',GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),'')
         FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_id_segment' AND INDEX_NAME='PRIMARY')='0:namespace'
);
DROP TEMPORARY TABLE commerce_invariant_shape_guard;
SET NAMES utf8mb4;

SET @commerce_order_idempotency_collation = (
    SELECT collation_name
    FROM information_schema.columns
    WHERE table_schema=DATABASE()
      AND table_name='commerce_order'
      AND column_name='idempotency_key'
);
SET @commerce_order_idempotency_ddl = IF(
    @commerce_order_idempotency_collation='utf8mb4_bin',
    'SELECT 1',
    'ALTER TABLE commerce_order MODIFY idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL'
);
PREPARE commerce_order_idempotency_stmt FROM @commerce_order_idempotency_ddl;
EXECUTE commerce_order_idempotency_stmt;
DEALLOCATE PREPARE commerce_order_idempotency_stmt;

SET @commerce_recovery_idempotency_collation = (
    SELECT collation_name
    FROM information_schema.columns
    WHERE table_schema=DATABASE()
      AND table_name='commerce_order_recovery'
      AND column_name='idempotency_key'
);
SET @commerce_recovery_idempotency_ddl = IF(
    @commerce_recovery_idempotency_collation='utf8mb4_bin',
    'SELECT 1',
    'ALTER TABLE commerce_order_recovery MODIFY idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NULL'
);
PREPARE commerce_recovery_idempotency_stmt FROM @commerce_recovery_idempotency_ddl;
EXECUTE commerce_recovery_idempotency_stmt;
DEALLOCATE PREPARE commerce_recovery_idempotency_stmt;

CREATE TABLE IF NOT EXISTS commerce_id_segment (
    namespace VARCHAR(32) NOT NULL,
    next_value BIGINT NOT NULL,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (namespace)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO commerce_id_segment(namespace,next_value)
SELECT 'order',COALESCE(MAX(id),0) FROM commerce_order
ON DUPLICATE KEY UPDATE next_value=GREATEST(next_value,VALUES(next_value));

INSERT INTO commerce_id_segment(namespace,next_value)
SELECT 'voucher',COALESCE(MAX(id),0) FROM commerce_voucher
ON DUPLICATE KEY UPDATE next_value=GREATEST(next_value,VALUES(next_value));

SET @outbox_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema=DATABASE()
      AND table_name='commerce_outbox_event'
      AND index_name='uk_commerce_outbox_aggregate_version'
);
SET @outbox_index_ddl = IF(
    @outbox_index_exists=0,
    'ALTER TABLE commerce_outbox_event ADD UNIQUE KEY uk_commerce_outbox_aggregate_version (aggregate_type,aggregate_id,event_type,aggregate_version)',
    'SELECT 1'
);
PREPARE commerce_outbox_index_stmt FROM @outbox_index_ddl;
EXECUTE commerce_outbox_index_stmt;
DEALLOCATE PREPARE commerce_outbox_index_stmt;

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

CREATE TEMPORARY TABLE commerce_invariant_result_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_commerce_invariant_result_guard CHECK (valid = 1)
);
INSERT INTO commerce_invariant_result_guard(valid)
SELECT ((SELECT CONCAT(
  (SELECT COUNT(*) FROM commerce_id_segment WHERE namespace IN ('order','voucher')),
  ':',
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_outbox_event'
     AND INDEX_NAME='uk_commerce_outbox_aggregate_version'),
  ':',
  (SELECT COLLATION_NAME FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_order'
     AND COLUMN_NAME='idempotency_key'),
  ':',
  (SELECT COLLATION_NAME FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_order_recovery'
     AND COLUMN_NAME='idempotency_key')
)) = '2:4:utf8mb4_bin:utf8mb4_bin');
DROP TEMPORARY TABLE commerce_invariant_result_guard;
SELECT namespace,next_value,update_time FROM commerce_id_segment LIMIT 0;
