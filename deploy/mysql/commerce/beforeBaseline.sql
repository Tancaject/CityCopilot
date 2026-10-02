-- Explicit adoption only: the caller must set baselineVersion=1.
-- Refuse schemas outside the supported Commerce lineage; no business rows are changed.
SELECT id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status,version,create_time,update_time FROM commerce_voucher LIMIT 0;
SELECT voucher_id,stock,begin_time,end_time,version,update_time FROM commerce_seckill_voucher LIMIT 0;
SELECT shop_id,shop_name,owner_user_id,source_version,updated_at FROM shop_summary_projection LIMIT 0;
SELECT id,user_id,voucher_id,shop_id,shop_name_snapshot,voucher_title_snapshot,voucher_sub_title_snapshot,voucher_rules_snapshot,pay_value_snapshot,actual_value_snapshot,pay_type,status,version,idempotency_key,create_time,pay_time,use_time,refund_time,update_time FROM commerce_order LIMIT 0;
SELECT order_id,voucher_id,user_id,status,version,stream_record_id,failure_code,created_at,updated_at FROM commerce_reservation LIMIT 0;
SELECT event_id,event_type,aggregate_type,aggregate_id,aggregate_version,topic,payload_json,status,claim_token,fencing_token,claimed_until,attempts,next_attempt_at,published_at,last_error,created_at,updated_at FROM commerce_outbox_event LIMIT 0;
SELECT consumer_group,event_id,payload_hash,status,processed_at FROM commerce_inbox_event LIMIT 0;
SELECT source_id,payload_json,error_message,retry_count,created_at,replayed_at FROM commerce_dead_letter LIMIT 0;
SELECT source_id,source_type,order_id,voucher_id,user_id,idempotency_key,payload_json,phase,claim_token,claim_owner,claim_version,claimed_until,inventory_fence_token,compensation_delta,last_error,terminal_at,finalized_at,created_at,updated_at FROM commerce_order_recovery LIMIT 0;

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
CREATE TEMPORARY TABLE commerce_baseline_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_commerce_baseline_guard CHECK (valid = 1)
);
INSERT INTO commerce_baseline_guard(valid)
SELECT COUNT(*)=9 FROM information_schema.TABLES
WHERE TABLE_SCHEMA=DATABASE() AND ENGINE='InnoDB' AND TABLE_NAME IN (
    'commerce_voucher','commerce_seckill_voucher','shop_summary_projection',
    'commerce_order','commerce_reservation','commerce_outbox_event',
    'commerce_inbox_event','commerce_dead_letter','commerce_order_recovery');
INSERT INTO commerce_baseline_guard(valid)
SELECT COUNT(*)=11 FROM (
    SELECT TABLE_NAME,INDEX_NAME,MIN(NON_UNIQUE) AS non_unique,
           GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS columns_in_order
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE()
    GROUP BY TABLE_NAME,INDEX_NAME
) indexes_found
WHERE (TABLE_NAME='commerce_voucher' AND INDEX_NAME='PRIMARY' AND non_unique=0 AND columns_in_order='id')
   OR (TABLE_NAME='commerce_seckill_voucher' AND INDEX_NAME='PRIMARY' AND non_unique=0 AND columns_in_order='voucher_id')
   OR (TABLE_NAME='shop_summary_projection' AND INDEX_NAME='PRIMARY' AND non_unique=0 AND columns_in_order='shop_id')
   OR (TABLE_NAME='commerce_order' AND INDEX_NAME='PRIMARY' AND non_unique=0 AND columns_in_order='id')
   OR (TABLE_NAME='commerce_order' AND INDEX_NAME='uk_commerce_order_user_voucher' AND non_unique=0 AND columns_in_order='user_id,voucher_id')
   OR (TABLE_NAME='commerce_reservation' AND INDEX_NAME='PRIMARY' AND non_unique=0 AND columns_in_order='order_id')
   OR (TABLE_NAME='commerce_reservation' AND INDEX_NAME='uk_commerce_reservation_user_voucher' AND non_unique=0 AND columns_in_order='user_id,voucher_id')
   OR (TABLE_NAME='commerce_outbox_event' AND INDEX_NAME='PRIMARY' AND non_unique=0 AND columns_in_order='event_id')
   OR (TABLE_NAME='commerce_inbox_event' AND INDEX_NAME='PRIMARY' AND non_unique=0 AND columns_in_order='consumer_group,event_id')
   OR (TABLE_NAME='commerce_dead_letter' AND INDEX_NAME='PRIMARY' AND non_unique=0 AND columns_in_order='source_id')
   OR (TABLE_NAME='commerce_order_recovery' AND INDEX_NAME='PRIMARY' AND non_unique=0 AND columns_in_order='source_id');
DROP TEMPORARY TABLE commerce_baseline_guard;
