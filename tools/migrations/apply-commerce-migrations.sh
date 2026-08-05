#!/bin/sh
set -eu

export MYSQL_PWD="${MYSQL_PASSWORD}"

admission_ledger_migration="${COMMERCE_ADMISSION_LEDGER_MIGRATION:-/opt/citycopilot/commerce-schema/003-commerce-admission-ledger.sql}"

if [ ! -r "${admission_ledger_migration}" ]; then
  echo "Commerce admission-ledger migration is not readable at ${admission_ledger_migration}." >&2
  exit 1
fi

idempotency_identity_preflight="$(
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT CONCAT_WS('|',
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
);
SQL
)"

expected_idempotency_identity_shape="1|1|0:user_id,idempotency_key|0:source_id"
if [ "${idempotency_identity_preflight}" != "${expected_idempotency_identity_shape}" ]; then
  echo "Commerce idempotency identity columns/indexes have an unexpected preflight shape: ${idempotency_identity_preflight}" >&2
  exit 1
fi

ledger_table_count="$(
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT COUNT(*)
FROM information_schema.TABLES
WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='commerce_admission_ledger';
SQL
)"

if [ "${ledger_table_count}" != "0" ] && [ "${ledger_table_count}" != "1" ]; then
  echo "Commerce admission ledger has an unexpected table count (${ledger_table_count})." >&2
  exit 1
fi

if [ "${ledger_table_count}" = "1" ]; then
  ledger_preflight_shape="$(
    mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT CONCAT_WS('|',
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
);
SQL
  )"
  expected_ledger_shape="20|3|0:order_id|0:user_id,idempotency_key|1:routing_generation,physical_shard_count,status,accepted_at,claimed_until|InnoDB|1"
  if [ "${ledger_preflight_shape}" != "${expected_ledger_shape}" ]; then
    echo "Commerce admission ledger has an unexpected preflight shape: ${ledger_preflight_shape}" >&2
    exit 1
  fi
fi

mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" < "${admission_ledger_migration}"
echo "Applied/verified the Commerce admission ledger and legacy-order identity backfill."

index_count="$(
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT COUNT(*)
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'commerce_outbox_event'
  AND INDEX_NAME = 'idx_commerce_outbox_claim';
SQL
)"

if [ "${index_count}" = "0" ]; then
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" \
    < /opt/citycopilot/migrations/2026-08-04-commerce-outbox-claim-index.sql
  echo "Applied the Commerce Outbox expired-claim index migration."
elif [ "${index_count}" != "2" ]; then
  echo "Commerce Outbox claim index has an unexpected shape (${index_count}/2 columns)." >&2
  exit 1
fi

duplicate_outbox_aggregates="$(
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT COUNT(*) FROM (
  SELECT aggregate_type,aggregate_id,event_type,aggregate_version
  FROM commerce_outbox_event
  GROUP BY aggregate_type,aggregate_id,event_type,aggregate_version
  HAVING COUNT(*) > 1
) duplicate_aggregates;
SQL
)"

if [ "${duplicate_outbox_aggregates}" != "0" ]; then
  echo "Commerce Outbox contains ${duplicate_outbox_aggregates} duplicate aggregate-version groups; refusing to add the uniqueness invariant." >&2
  exit 1
fi

outbox_unique_shape="$(
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT COALESCE(
  CONCAT(MIN(NON_UNIQUE),':',GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')),
  ''
)
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='commerce_outbox_event'
  AND INDEX_NAME='uk_commerce_outbox_aggregate_version';
SQL
)"

if [ -n "${outbox_unique_shape}" ] && [ "${outbox_unique_shape}" != "0:aggregate_type,aggregate_id,event_type,aggregate_version" ]; then
  echo "Commerce Outbox aggregate-version index has an unexpected shape: ${outbox_unique_shape}" >&2
  exit 1
fi

mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" \
  < /opt/citycopilot/migrations/2026-08-04-commerce-production-invariants.sql
echo "Applied/verified durable Commerce ID segments and Outbox aggregate-version uniqueness."

postflight="$(
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT CONCAT(
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
);
SQL
)"

if [ "${postflight}" != "2:4:utf8mb4_bin:utf8mb4_bin" ]; then
  echo "Commerce production invariant migration postflight failed (${postflight}, expected 2:4:utf8mb4_bin:utf8mb4_bin)." >&2
  exit 1
fi

ledger_postflight="$(
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT CONCAT_WS('|',
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
);
SQL
)"

expected_ledger_postflight="20|3|0:order_id|0:user_id,idempotency_key|1:routing_generation,physical_shard_count,status,accepted_at,claimed_until|InnoDB|1|0|0"
if [ "${ledger_postflight}" != "${expected_ledger_postflight}" ]; then
  echo "Commerce admission-ledger migration postflight failed (${ledger_postflight})." >&2
  exit 1
fi

echo "Commerce schema migrations are up to date."
