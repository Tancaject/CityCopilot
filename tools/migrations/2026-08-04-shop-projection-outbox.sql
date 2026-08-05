-- Durable shop projection change log and cross-instance maintenance lease.
-- Apply after 2026-07-30-shop-geo-coordinate-contract.sql.

ALTER TABLE `shop`
  ADD COLUMN `projection_version` BIGINT NOT NULL DEFAULT 0
    COMMENT 'monotonic source version for rebuildable projections' AFTER `update_time`;

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

-- Every outbox append takes a shared lock on this row. Rebuild cutover takes
-- the exclusive lock only while closing a watermark and publishing the new
-- ES alias / Redis generation, so the long base scan never blocks API writes.
INSERT INTO `shop_projection_lease` (`lease_name`, `fencing_token`)
VALUES ('shop-projection-cutover', 0)
ON DUPLICATE KEY UPDATE `lease_name` = VALUES(`lease_name`);
