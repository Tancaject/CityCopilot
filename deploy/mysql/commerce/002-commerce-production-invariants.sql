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
