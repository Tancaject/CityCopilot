-- Reject partial legacy installation instead of guessing which objects to repair.
CREATE TEMPORARY TABLE core_projection_guard (valid BOOLEAN NOT NULL, CONSTRAINT chk_core_projection_migration_shape CHECK (valid = TRUE));
SET @projection_column = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shop' AND COLUMN_NAME='projection_version');
SET @projection_tables = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('shop_projection_outbox','shop_projection_lease'));
INSERT INTO core_projection_guard VALUES ((@projection_column + @projection_tables) IN (0,3));
INSERT INTO core_projection_guard
SELECT @projection_column=0 OR COUNT(*)=1 FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shop' AND COLUMN_NAME='projection_version'
  AND DATA_TYPE='bigint' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT='0';
INSERT INTO core_projection_guard
SELECT @projection_tables=0 OR COUNT(*)=2 FROM information_schema.TABLES
WHERE TABLE_SCHEMA=DATABASE() AND ENGINE='InnoDB'
  AND TABLE_NAME IN ('shop_projection_outbox','shop_projection_lease');
INSERT INTO core_projection_guard
SELECT @projection_tables=0 OR COUNT(*)=4 FROM (
    SELECT TABLE_NAME,INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_key
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND NON_UNIQUE=0
    GROUP BY TABLE_NAME,INDEX_NAME
    HAVING SUM(SUB_PART IS NOT NULL)=0
) actual
WHERE (TABLE_NAME,INDEX_NAME,columns_in_key) IN (
    ('shop_projection_outbox','PRIMARY','id'),
    ('shop_projection_outbox','uk_shop_projection_event','event_id'),
    ('shop_projection_outbox','uk_shop_projection_version','shop_id,aggregate_version'),
    ('shop_projection_lease','PRIMARY','lease_name')
);
INSERT INTO core_projection_guard
SELECT @projection_tables=0 OR COUNT(*)=3 FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=DATABASE() AND DATA_TYPE='bigint' AND IS_NULLABLE='NO'
  AND ((TABLE_NAME='shop_projection_outbox' AND COLUMN_NAME='id' AND EXTRA LIKE '%auto_increment%' AND COLUMN_TYPE LIKE '%unsigned%')
    OR (TABLE_NAME='shop_projection_outbox' AND COLUMN_NAME='aggregate_version')
    OR (TABLE_NAME='shop_projection_lease' AND COLUMN_NAME='fencing_token' AND COLUMN_DEFAULT='0'));
SET @projection_ddl = IF(@projection_column=0, 'ALTER TABLE `shop`
  ADD COLUMN `projection_version` BIGINT NOT NULL DEFAULT 0
    COMMENT ''monotonic source version for rebuildable projections'' AFTER `update_time`', 'SELECT 1');
PREPARE core_projection_statement FROM @projection_ddl;
EXECUTE core_projection_statement;
DEALLOCATE PREPARE core_projection_statement;
DROP TEMPORARY TABLE core_projection_guard;

CREATE TABLE IF NOT EXISTS `shop_projection_outbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_id` CHAR(36) NOT NULL,
  `shop_id` BIGINT UNSIGNED NOT NULL,
  `aggregate_version` BIGINT NOT NULL,
  `event_type` VARCHAR(64) NOT NULL DEFAULT 'ShopChanged',
  `payload_json` LONGTEXT NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'READY',
  `claim_token` CHAR(36) NULL,
  `claim_owner` VARCHAR(128) NULL,
  `claimed_until` TIMESTAMP(3) NULL,
  `attempts` INT NOT NULL DEFAULT 0,
  `next_attempt_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `processed_at` TIMESTAMP(3) NULL,
  `last_error` VARCHAR(1024) NULL,
  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop_projection_event` (`event_id`),
  UNIQUE KEY `uk_shop_projection_version` (`shop_id`, `aggregate_version`),
  KEY `idx_shop_projection_ready` (`status`, `next_attempt_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `shop_projection_lease` (
  `lease_name` VARCHAR(128) NOT NULL,
  `owner_id` VARCHAR(128) NULL,
  `fencing_token` BIGINT NOT NULL DEFAULT 0,
  `lease_until` TIMESTAMP(3) NULL,
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`lease_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing same-name tables must expose every field used by projection workers before seeding the barrier.
SELECT id,event_id,shop_id,aggregate_version,event_type,payload_json,status,claim_token,claim_owner,
       claimed_until,attempts,next_attempt_at,processed_at,last_error,created_at,updated_at
FROM shop_projection_outbox LIMIT 0;
SELECT lease_name,owner_id,fencing_token,lease_until,updated_at FROM shop_projection_lease LIMIT 0;

-- Every outbox append takes a shared lock on this row. Rebuild cutover takes
-- the exclusive lock only while closing a watermark and publishing the new
-- ES alias / Redis generation, so the long base scan never blocks API writes.
INSERT INTO `shop_projection_lease` (`lease_name`, `fencing_token`)
VALUES ('shop-projection-cutover', 0)
ON DUPLICATE KEY UPDATE `lease_name` = VALUES(`lease_name`);
