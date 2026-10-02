SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_booking_resource (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    kind VARCHAR(24) NOT NULL,
    name VARCHAR(160) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    max_party_size INT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_booking_resource_kind CHECK (kind IN ('COURT','DINING_POOL')),
    CONSTRAINT chk_booking_resource_status CHECK (status IN ('OPEN','STOP_NEW','UNAVAILABLE')),
    CONSTRAINT chk_booking_resource_party CHECK (max_party_size > 0),
    KEY idx_booking_resource_shop (shop_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_booking_slot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    resource_id BIGINT NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    capacity INT NOT NULL,
    occupied INT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_booking_slot_resource FOREIGN KEY (resource_id) REFERENCES commerce_booking_resource(id),
    CONSTRAINT chk_booking_slot_capacity CHECK (capacity > 0 AND occupied >= 0 AND occupied <= capacity),
    CONSTRAINT chk_booking_slot_time CHECK (end_at > start_at),
    CONSTRAINT chk_booking_slot_status CHECK (status IN ('OPEN','STOP_NEW','UNAVAILABLE')),
    UNIQUE KEY uk_booking_slot_start (resource_id,start_at),
    KEY idx_booking_slot_calendar (resource_id,start_at,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_booking_user_guard (
    user_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS commerce_booking (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    resource_name VARCHAR(160) NOT NULL,
    resource_kind VARCHAR(24) NOT NULL,
    party_size INT NOT NULL,
    units INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    reason_code VARCHAR(255) NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    hold_expires_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    schedule_version BIGINT NOT NULL DEFAULT 0,
    price_snapshot JSON NOT NULL,
    checkin_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source VARCHAR(32) NULL,
    impression_id VARCHAR(128) NULL,
    request_id VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    used_at DATETIME(3) NULL,
    CONSTRAINT fk_booking_resource FOREIGN KEY (resource_id) REFERENCES commerce_booking_resource(id),
    CONSTRAINT fk_booking_slot FOREIGN KEY (slot_id) REFERENCES commerce_booking_slot(id),
    CONSTRAINT chk_booking_status CHECK (status IN ('HELD','CONFIRMED','CANCELLED','HOLD_EXPIRED','USED','NO_SHOW')),
    CONSTRAINT chk_booking_units CHECK (party_size > 0 AND units > 0),
    KEY idx_booking_user_time (user_id,status,start_at),
    KEY idx_booking_shop_time (shop_id,start_at),
    KEY idx_booking_expiry (status,hold_expires_at),
    KEY idx_booking_end (status,end_at),
    KEY idx_booking_slot (slot_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_booking_command (
    actor_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    action VARCHAR(32) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    booking_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (actor_id,idempotency_key),
    CONSTRAINT fk_booking_command_booking FOREIGN KEY (booking_id) REFERENCES commerce_booking(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_booking_reminder (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    booking_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schedule_version BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    message VARCHAR(512) NOT NULL,
    due_at DATETIME(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    delivered_at DATETIME(3) NULL,
    read_at DATETIME(3) NULL,
    CONSTRAINT fk_booking_reminder_booking FOREIGN KEY (booking_id) REFERENCES commerce_booking(id),
    CONSTRAINT chk_booking_reminder_status CHECK (status IN ('PENDING','VISIBLE','CANCELLED')),
    UNIQUE KEY uk_booking_reminder (booking_id,schedule_version,kind),
    KEY idx_booking_reminder_due (status,due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TEMPORARY TABLE commerce_booking_shape_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_commerce_booking_shape_guard CHECK (valid = 1)
);
INSERT INTO commerce_booking_shape_guard(valid)
SELECT ((SELECT CONCAT_WS('|',
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND ENGINE='InnoDB'
   AND TABLE_NAME IN ('commerce_booking_resource','commerce_booking_slot','commerce_booking',
                     'commerce_booking_user_guard','commerce_booking_command','commerce_booking_reminder')),
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE()
   AND CONSTRAINT_TYPE='CHECK' AND CONSTRAINT_NAME IN ('chk_booking_resource_kind','chk_booking_resource_status',
    'chk_booking_resource_party','chk_booking_slot_capacity','chk_booking_slot_time','chk_booking_slot_status',
    'chk_booking_status','chk_booking_units','chk_booking_reminder_status')),
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE()
   AND CONSTRAINT_TYPE='FOREIGN KEY' AND CONSTRAINT_NAME IN ('fk_booking_slot_resource','fk_booking_resource',
    'fk_booking_slot','fk_booking_command_booking','fk_booking_reminder_booking')),
  (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_booking_command' AND INDEX_NAME='PRIMARY'),
  (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_booking_slot' AND INDEX_NAME='uk_booking_slot_start' AND NON_UNIQUE=0),
  (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_booking_reminder' AND INDEX_NAME='uk_booking_reminder' AND NON_UNIQUE=0))) = '6|9|5|actor_id,idempotency_key|resource_id,start_at|booking_id,schedule_version,kind');
DROP TEMPORARY TABLE commerce_booking_shape_guard;
SELECT id,shop_id,kind,name,timezone,max_party_size,status,version,created_at FROM commerce_booking_resource LIMIT 0;
SELECT id,resource_id,start_at,end_at,capacity,occupied,status,version FROM commerce_booking_slot LIMIT 0;
SELECT user_id FROM commerce_booking_user_guard LIMIT 0;
SELECT id,user_id,shop_id,resource_id,slot_id,resource_name,resource_kind,party_size,units,status,reason_code,start_at,end_at,hold_expires_at,version,schedule_version,price_snapshot,checkin_token_hash,source,impression_id,request_id,created_at,updated_at,used_at FROM commerce_booking LIMIT 0;
SELECT actor_id,idempotency_key,action,request_hash,booking_id,result_json,created_at FROM commerce_booking_command LIMIT 0;
SELECT id,booking_id,schedule_version,kind,message,due_at,status,delivered_at,read_at FROM commerce_booking_reminder LIMIT 0;
