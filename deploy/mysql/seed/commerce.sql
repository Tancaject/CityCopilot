-- Development sample data only; execute after Commerce migrations.
INSERT IGNORE INTO shop_summary_projection(shop_id,shop_name,owner_user_id,source_version)
VALUES (1,'Commerce Migration Demo Shop',1,1);

INSERT IGNORE INTO commerce_voucher(
    id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status
) VALUES (100,1,'Commerce Seckill Demo','Independent transaction service','One voucher per user',8000,5000,1,1);

INSERT IGNORE INTO commerce_seckill_voucher(voucher_id,stock,begin_time,end_time)
VALUES (100,100,CURRENT_TIMESTAMP(3)-INTERVAL 1 DAY,CURRENT_TIMESTAMP(3)+INTERVAL 30 DAY);


-- Samples reserve explicit IDs; keep the durable voucher allocator ahead of them.
UPDATE commerce_id_segment
SET next_value=GREATEST(next_value,(SELECT COALESCE(MAX(id),0) FROM commerce_voucher))
WHERE namespace='voucher';
