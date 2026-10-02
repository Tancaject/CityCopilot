-- Validate an already-created ledger before the historical-order backfill touches it.
CREATE TEMPORARY TABLE commerce_ledger_shape_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_commerce_ledger_shape_guard CHECK (valid = 1)
);
INSERT INTO commerce_ledger_shape_guard(valid)
SELECT NOT EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_admission_ledger')
    OR ((SELECT CONCAT_WS('|',
  (SELECT COUNT(*)
   FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND COLUMN_NAME IN (
       'order_id','user_id','idempotency_key','voucher_id','request_fingerprint',
       'routing_generation','physical_shard_count','status','rejection_code',
       'rejection_http_status','rejection_message','attempts','claim_token',
       'claim_owner','claimed_until','accepted_at','terminal_at','last_error',
       'created_at','updated_at'
     )),
  (SELECT COUNT(*)
   FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND COLUMN_NAME IN ('idempotency_key','request_fingerprint','routing_generation')
     AND COLLATION_NAME='utf8mb4_bin'),
  (SELECT COALESCE(CONCAT(MIN(NON_UNIQUE),':',
          GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),'')
   FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND INDEX_NAME='PRIMARY'),
  (SELECT COALESCE(CONCAT(MIN(NON_UNIQUE),':',
          GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),'')
   FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND INDEX_NAME='uk_commerce_admission_user_idempotency'),
  (SELECT COALESCE(CONCAT(MIN(NON_UNIQUE),':',
          GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),'')
   FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND INDEX_NAME='idx_commerce_admission_recovery'),
  (SELECT COALESCE(MAX(ENGINE),'')
   FROM information_schema.TABLES
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'),
  (SELECT COUNT(*)
   FROM information_schema.TABLE_CONSTRAINTS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND CONSTRAINT_TYPE='CHECK'
     AND CONSTRAINT_NAME='chk_commerce_admission_status')
)) = '20|3|0:order_id|0:user_id,idempotency_key|1:routing_generation,physical_shard_count,status,accepted_at,claimed_until|InnoDB|1');
DROP TEMPORARY TABLE commerce_ledger_shape_guard;
SET NAMES utf8mb4;

-- Durable acceptance ledger for the two Redis Cluster slots involved in a
-- Commerce admission. PREPARED is an identity reservation only; background
-- recovery is allowed to claim ACCEPTED rows exclusively.
CREATE TABLE IF NOT EXISTS commerce_admission_ledger (
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    voucher_id BIGINT NOT NULL,
    request_fingerprint VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    routing_generation VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    physical_shard_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    rejection_code VARCHAR(64) NULL,
    rejection_http_status SMALLINT NULL,
    rejection_message VARCHAR(255) NULL,
    attempts INT NOT NULL DEFAULT 0,
    claim_token CHAR(36) NULL,
    claim_owner VARCHAR(191) NULL,
    claimed_until TIMESTAMP(3) NULL,
    accepted_at TIMESTAMP(3) NULL,
    terminal_at TIMESTAMP(3) NULL,
    last_error VARCHAR(1024) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_commerce_admission_user_idempotency (user_id, idempotency_key),
    KEY idx_commerce_admission_recovery (
        routing_generation, physical_shard_count, status, accepted_at, claimed_until
    ),
    CONSTRAINT chk_commerce_admission_status CHECK (
        status IN ('PREPARED','ACCEPTED','REJECTED','ORDER_CREATED','REPAIR_REQUIRED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Upgrade backfill. Admission must be stopped and every pre-ledger Stream
-- record drained before this migration runs. The temporary CHECK guard makes
-- any order/ledger identity collision a hard migration failure; it must never
-- be converted into INSERT IGNORE because that would permit a second accepted
-- order for the same idempotency key after rollout.
DROP TEMPORARY TABLE IF EXISTS commerce_admission_backfill_guard;
CREATE TEMPORARY TABLE commerce_admission_backfill_guard (
    conflict_count BIGINT NOT NULL,
    CONSTRAINT chk_commerce_admission_backfill_zero CHECK (conflict_count = 0)
);

INSERT INTO commerce_admission_backfill_guard(conflict_count)
SELECT COUNT(*)
FROM commerce_order existing_order
JOIN commerce_admission_ledger ledger
  ON ledger.order_id = existing_order.id
  OR (
      ledger.user_id = existing_order.user_id
      AND BINARY ledger.idempotency_key = BINARY existing_order.idempotency_key
  )
WHERE CHAR_LENGTH(existing_order.idempotency_key) > 0
  AND NOT (
      ledger.order_id = existing_order.id
      AND ledger.user_id = existing_order.user_id
      AND ledger.voucher_id = existing_order.voucher_id
      AND BINARY ledger.idempotency_key = BINARY existing_order.idempotency_key
      AND BINARY ledger.request_fingerprint =
          BINARY CONCAT('seckill:v1:', existing_order.voucher_id)
  );

START TRANSACTION;

INSERT INTO commerce_admission_ledger(
    order_id,user_id,idempotency_key,voucher_id,request_fingerprint,
    routing_generation,physical_shard_count,status,attempts,
    accepted_at,terminal_at,created_at,updated_at
)
SELECT
    existing_order.id,
    existing_order.user_id,
    existing_order.idempotency_key,
    existing_order.voucher_id,
    CONCAT('seckill:v1:', existing_order.voucher_id),
    'legacy-order-v0',
    0,
    'ORDER_CREATED',
    0,
    existing_order.create_time,
    existing_order.update_time,
    existing_order.create_time,
    existing_order.update_time
FROM commerce_order existing_order
LEFT JOIN commerce_admission_ledger ledger
  ON ledger.order_id = existing_order.id
WHERE CHAR_LENGTH(existing_order.idempotency_key) > 0
  AND ledger.order_id IS NULL;

-- An exact row may already exist after a prior partial rollout. The durable
-- MySQL order is authoritative, so terminalize that same identity without
-- changing its recorded routing generation.
UPDATE commerce_admission_ledger ledger
JOIN commerce_order existing_order
  ON ledger.order_id = existing_order.id
 AND ledger.user_id = existing_order.user_id
 AND ledger.voucher_id = existing_order.voucher_id
 AND BINARY ledger.idempotency_key = BINARY existing_order.idempotency_key
 AND BINARY ledger.request_fingerprint =
     BINARY CONCAT('seckill:v1:', existing_order.voucher_id)
SET ledger.status = 'ORDER_CREATED',
    ledger.accepted_at = COALESCE(ledger.accepted_at, existing_order.create_time),
    ledger.terminal_at = COALESCE(ledger.terminal_at, existing_order.update_time),
    ledger.rejection_code = NULL,
    ledger.rejection_http_status = NULL,
    ledger.rejection_message = NULL,
    ledger.last_error = NULL,
    ledger.claim_token = NULL,
    ledger.claim_owner = NULL,
    ledger.claimed_until = NULL
WHERE CHAR_LENGTH(existing_order.idempotency_key) > 0
  AND ledger.status <> 'ORDER_CREATED';

DELETE FROM commerce_admission_backfill_guard;
INSERT INTO commerce_admission_backfill_guard(conflict_count)
SELECT COUNT(*)
FROM commerce_order existing_order
LEFT JOIN commerce_admission_ledger ledger
  ON ledger.order_id = existing_order.id
 AND ledger.user_id = existing_order.user_id
 AND ledger.voucher_id = existing_order.voucher_id
 AND BINARY ledger.idempotency_key = BINARY existing_order.idempotency_key
 AND BINARY ledger.request_fingerprint =
     BINARY CONCAT('seckill:v1:', existing_order.voucher_id)
 AND ledger.status = 'ORDER_CREATED'
WHERE CHAR_LENGTH(existing_order.idempotency_key) > 0
  AND ledger.order_id IS NULL;

COMMIT;
DROP TEMPORARY TABLE commerce_admission_backfill_guard;

CREATE TEMPORARY TABLE commerce_ledger_result_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_commerce_ledger_result_guard CHECK (valid = 1)
);
INSERT INTO commerce_ledger_result_guard(valid)
SELECT ((SELECT CONCAT_WS('|',
  (SELECT COUNT(*)
   FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND COLUMN_NAME IN (
       'order_id','user_id','idempotency_key','voucher_id','request_fingerprint',
       'routing_generation','physical_shard_count','status','rejection_code',
       'rejection_http_status','rejection_message','attempts','claim_token',
       'claim_owner','claimed_until','accepted_at','terminal_at','last_error',
       'created_at','updated_at'
     )),
  (SELECT COUNT(*)
   FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND COLUMN_NAME IN ('idempotency_key','request_fingerprint','routing_generation')
     AND COLLATION_NAME='utf8mb4_bin'),
  (SELECT COALESCE(CONCAT(MIN(NON_UNIQUE),':',
          GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),'')
   FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND INDEX_NAME='PRIMARY'),
  (SELECT COALESCE(CONCAT(MIN(NON_UNIQUE),':',
          GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),'')
   FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND INDEX_NAME='uk_commerce_admission_user_idempotency'),
  (SELECT COALESCE(CONCAT(MIN(NON_UNIQUE),':',
          GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),'')
   FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND INDEX_NAME='idx_commerce_admission_recovery'),
  (SELECT COALESCE(MAX(ENGINE),'')
   FROM information_schema.TABLES
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'),
  (SELECT COUNT(*)
   FROM information_schema.TABLE_CONSTRAINTS
   WHERE TABLE_SCHEMA=DATABASE()
     AND TABLE_NAME='commerce_admission_ledger'
     AND CONSTRAINT_TYPE='CHECK'
     AND CONSTRAINT_NAME='chk_commerce_admission_status'),
  (SELECT COUNT(*)
   FROM commerce_order existing_order
   JOIN commerce_admission_ledger ledger
     ON ledger.order_id=existing_order.id
     OR (ledger.user_id=existing_order.user_id
         AND BINARY ledger.idempotency_key=BINARY existing_order.idempotency_key)
   WHERE CHAR_LENGTH(existing_order.idempotency_key)>0
     AND NOT (
       ledger.order_id=existing_order.id
       AND ledger.user_id=existing_order.user_id
       AND ledger.voucher_id=existing_order.voucher_id
       AND BINARY ledger.idempotency_key=BINARY existing_order.idempotency_key
       AND BINARY ledger.request_fingerprint=
           BINARY CONCAT('seckill:v1:',existing_order.voucher_id)
     )),
  (SELECT COUNT(*)
   FROM commerce_order existing_order
   LEFT JOIN commerce_admission_ledger ledger
     ON ledger.order_id=existing_order.id
    AND ledger.user_id=existing_order.user_id
    AND ledger.voucher_id=existing_order.voucher_id
    AND BINARY ledger.idempotency_key=BINARY existing_order.idempotency_key
    AND BINARY ledger.request_fingerprint=
        BINARY CONCAT('seckill:v1:',existing_order.voucher_id)
    AND ledger.status='ORDER_CREATED'
   WHERE CHAR_LENGTH(existing_order.idempotency_key)>0
     AND ledger.order_id IS NULL)
)) = '20|3|0:order_id|0:user_id,idempotency_key|1:routing_generation,physical_shard_count,status,accepted_at,claimed_until|InnoDB|1|0|0');
DROP TEMPORARY TABLE commerce_ledger_result_guard;
