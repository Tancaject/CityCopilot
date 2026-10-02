-- Explicit legacy adoption supports the current Core table names only.
SELECT `id`,`shop_id`,`user_id`,`title`,`images`,`content`,`liked`,`comments`,`create_time`,`update_time` FROM `note` LIMIT 0;
SELECT `id`,`user_id`,`blog_id`,`parent_id`,`answer_id`,`content`,`liked`,`status`,`create_time`,`update_time` FROM `note_comment` LIMIT 0;
SELECT `id`,`user_id`,`follow_user_id`,`create_time` FROM `user_follow` LIMIT 0;
SELECT `id`,`name`,`type_id`,`images`,`area`,`address`,`x`,`y`,`avg_price`,`sold`,`comments`,`score`,`open_hours`,`create_time`,`update_time`,`poi_id`,`adcode`,`geo_source`,`geo_status`,`geo_updated_at`,`owner_user_id` FROM `shop` LIMIT 0;
SELECT `id`,`name`,`icon`,`sort`,`create_time`,`update_time` FROM `shop_type` LIMIT 0;
SELECT `id`,`user_id`,`year`,`month`,`date`,`is_backup` FROM `user_checkin` LIMIT 0;
SELECT `id`,`phone`,`password`,`nick_name`,`icon`,`create_time`,`update_time`,`role` FROM `user_account` LIMIT 0;
SELECT `user_id`,`city`,`introduce`,`fans`,`followee`,`gender`,`birthday`,`credits`,`level`,`create_time`,`update_time` FROM `user_profile` LIMIT 0;
SELECT `id`,`actor_id`,`actor_name`,`actor_role`,`module`,`action`,`target_type`,`target_id`,`target_key`,`before_data`,`after_data`,`success`,`error_message`,`create_time` FROM `operation_log` LIMIT 0;
SELECT `id`,`job_id`,`status`,`city`,`keyword`,`verified`,`only_missing`,`limit_size`,`total`,`processed`,`success`,`failed`,`message`,`operator_id`,`operator_name`,`started_at`,`finished_at`,`create_time`,`update_time` FROM `geo_batch_job` LIMIT 0;
SELECT `id`,`job_id`,`shop_id`,`shop_name`,`status`,`message`,`before_data`,`after_data`,`create_time`,`update_time` FROM `geo_batch_job_item` LIMIT 0;
SELECT `consumer_group`,`event_id`,`payload_hash`,`event_type`,`aggregate_id`,`aggregate_version`,`status`,`quarantine_reason`,`processed_at` FROM `processed_event` LIMIT 0;
SELECT `consumer_group`,`aggregate_type`,`aggregate_id`,`current_version`,`updated_at` FROM `event_projection_version` LIMIT 0;
CREATE TEMPORARY TABLE core_baseline_guard (valid BOOLEAN NOT NULL, CONSTRAINT chk_core_baseline_identity CHECK (valid = TRUE));
INSERT INTO core_baseline_guard SELECT COUNT(*) = 13 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND ENGINE='InnoDB' AND TABLE_NAME IN ('note','note_comment','user_follow','shop','shop_type','user_checkin','user_account','user_profile','operation_log','geo_batch_job','geo_batch_job_item','processed_event','event_projection_version');
INSERT INTO core_baseline_guard
SELECT COUNT(*)=15 FROM (
    SELECT TABLE_NAME,INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_key
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND NON_UNIQUE=0
    GROUP BY TABLE_NAME,INDEX_NAME
    HAVING SUM(SUB_PART IS NOT NULL)=0
) actual
WHERE (TABLE_NAME,INDEX_NAME,columns_in_key) IN (
    ('note','PRIMARY','id'),
    ('note_comment','PRIMARY','id'),
    ('user_follow','PRIMARY','id'),
    ('shop','PRIMARY','id'),
    ('shop_type','PRIMARY','id'),
    ('user_checkin','PRIMARY','id'),
    ('user_account','PRIMARY','id'),
    ('user_account','uniqe_key_phone','phone'),
    ('user_profile','PRIMARY','user_id'),
    ('operation_log','PRIMARY','id'),
    ('geo_batch_job','PRIMARY','id'),
    ('geo_batch_job','uk_geo_batch_job_id','job_id'),
    ('geo_batch_job_item','PRIMARY','id'),
    ('processed_event','PRIMARY','consumer_group,event_id'),
    ('event_projection_version','PRIMARY','consumer_group,aggregate_type,aggregate_id')
);
INSERT INTO core_baseline_guard
SELECT COUNT(*)=2 FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=DATABASE() AND DATA_TYPE='bigint' AND IS_NULLABLE='NO'
  AND (TABLE_NAME,COLUMN_NAME) IN (
      ('processed_event','aggregate_version'),
      ('event_projection_version','current_version')
  );
DROP TEMPORARY TABLE core_baseline_guard;
