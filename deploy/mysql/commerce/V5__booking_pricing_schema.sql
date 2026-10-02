SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_booking_offer (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    resource_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    included_items VARCHAR(1000) NOT NULL DEFAULT '',
    excluded_items VARCHAR(1000) NOT NULL DEFAULT '',
    unit_price_cents BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    CONSTRAINT fk_booking_offer_resource FOREIGN KEY (resource_id) REFERENCES commerce_booking_resource(id),
    CONSTRAINT chk_booking_offer_kind CHECK (kind IN ('COURT_SLOT','PER_PERSON_PACKAGE')),
    CONSTRAINT chk_booking_offer_status CHECK (status IN ('OPEN','STOPPED')),
    CONSTRAINT chk_booking_offer_price CHECK (unit_price_cents BETWEEN 0 AND 922337203685477),
    KEY idx_booking_offer_resource (resource_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_booking_coupon_rule (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    campaign_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    rule_version BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    offer_id BIGINT NULL,
    type VARCHAR(24) NOT NULL,
    threshold_cents BIGINT NOT NULL,
    reduction_cents BIGINT NOT NULL,
    pay_factor_bps INT NOT NULL,
    discount_cap_cents BIGINT NULL,
    stackable BOOLEAN NOT NULL,
    service_from DATETIME(3) NOT NULL,
    service_to DATETIME(3) NOT NULL,
    claim_until DATETIME(3) NOT NULL,
    coupon_expires_at DATETIME(3) NOT NULL,
    issuance_status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    CONSTRAINT fk_booking_coupon_offer FOREIGN KEY (offer_id) REFERENCES commerce_booking_offer(id),
    CONSTRAINT chk_booking_coupon_type CHECK (type IN ('DISCOUNT','FULL_REDUCTION','CASH')),
    CONSTRAINT chk_booking_coupon_amount CHECK (
        threshold_cents BETWEEN 0 AND 922337203685477
        AND reduction_cents BETWEEN 0 AND 922337203685477
        AND pay_factor_bps BETWEEN 0 AND 10000
        AND (discount_cap_cents IS NULL OR discount_cap_cents BETWEEN 0 AND 922337203685477)),
    CONSTRAINT chk_booking_coupon_rule_time CHECK (service_to > service_from AND coupon_expires_at >= claim_until),
    CONSTRAINT chk_booking_coupon_issuance CHECK (issuance_status IN ('OPEN','STOPPED')),
    UNIQUE KEY uk_booking_coupon_version (campaign_key,rule_version),
    KEY idx_booking_coupon_shop (shop_id,issuance_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_user_booking_coupon (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    expires_at DATETIME(3) NOT NULL,
    booking_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revoked_at DATETIME(3) NULL,
    revoked_reason VARCHAR(255) NULL,
    claim_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_user_booking_coupon_rule FOREIGN KEY (rule_id) REFERENCES commerce_booking_coupon_rule(id),
    CONSTRAINT fk_user_booking_coupon_booking FOREIGN KEY (booking_id) REFERENCES commerce_booking(id),
    CONSTRAINT chk_user_booking_coupon_status CHECK (status IN ('AVAILABLE','RESERVED','CONSUMED','EXPIRED','REVOKED')),
    CONSTRAINT chk_user_booking_coupon_binding CHECK (
        (status IN ('RESERVED','CONSUMED') AND booking_id IS NOT NULL)
        OR (status NOT IN ('RESERVED','CONSUMED') AND booking_id IS NULL)),
    UNIQUE KEY uk_booking_coupon_claim (user_id,claim_key),
    UNIQUE KEY uk_booking_coupon_owner_rule (user_id,rule_id),
    KEY idx_user_booking_coupon (user_id,status),
    KEY idx_user_booking_coupon_binding (booking_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_booking_quote (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    offer_id BIGINT NULL,
    purpose VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    snapshot_json JSON NOT NULL,
    consumed_booking_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_booking_quote_resource FOREIGN KEY (resource_id) REFERENCES commerce_booking_resource(id),
    CONSTRAINT fk_booking_quote_offer FOREIGN KEY (offer_id) REFERENCES commerce_booking_offer(id),
    CONSTRAINT fk_booking_quote_consumed FOREIGN KEY (consumed_booking_id) REFERENCES commerce_booking(id),
    CONSTRAINT chk_booking_quote_purpose CHECK (purpose IN ('CREATE','RESCHEDULE')),
    KEY idx_booking_quote_user (user_id,created_at),
    KEY idx_booking_quote_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TEMPORARY TABLE commerce_pricing_shape_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_commerce_pricing_shape_guard CHECK (valid = 1)
);
INSERT INTO commerce_pricing_shape_guard(valid)
SELECT ((SELECT CONCAT_WS('|',
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND ENGINE='InnoDB'
   AND TABLE_NAME IN ('commerce_booking_offer','commerce_booking_coupon_rule','commerce_user_booking_coupon','commerce_booking_quote')),
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE()
   AND CONSTRAINT_TYPE='CHECK' AND CONSTRAINT_NAME IN ('chk_booking_offer_kind','chk_booking_offer_status','chk_booking_offer_price',
    'chk_booking_coupon_type','chk_booking_coupon_amount','chk_booking_coupon_rule_time','chk_booking_coupon_issuance',
    'chk_user_booking_coupon_status','chk_user_booking_coupon_binding','chk_booking_quote_purpose')),
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE()
   AND CONSTRAINT_TYPE='FOREIGN KEY' AND CONSTRAINT_NAME IN ('fk_booking_offer_resource','fk_booking_coupon_offer',
    'fk_user_booking_coupon_rule','fk_user_booking_coupon_booking','fk_booking_quote_resource','fk_booking_quote_offer','fk_booking_quote_consumed')),
  (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_user_booking_coupon' AND INDEX_NAME='uk_booking_coupon_claim' AND NON_UNIQUE=0),
  (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_user_booking_coupon' AND INDEX_NAME='uk_booking_coupon_owner_rule' AND NON_UNIQUE=0))) = '4|10|7|user_id,claim_key|user_id,rule_id');
DROP TEMPORARY TABLE commerce_pricing_shape_guard;
SELECT id,resource_id,shop_id,kind,title,included_items,excluded_items,unit_price_cents,version,status FROM commerce_booking_offer LIMIT 0;
SELECT id,campaign_key,rule_version,shop_id,offer_id,type,threshold_cents,reduction_cents,pay_factor_bps,discount_cap_cents,stackable,service_from,service_to,claim_until,coupon_expires_at,issuance_status FROM commerce_booking_coupon_rule LIMIT 0;
SELECT id,user_id,rule_id,status,expires_at,booking_id,revoked_at,revoked_reason,claim_key,created_at FROM commerce_user_booking_coupon LIMIT 0;
SELECT id,user_id,resource_id,offer_id,purpose,expires_at,snapshot_json,consumed_booking_id,created_at FROM commerce_booking_quote LIMIT 0;
