SET NAMES utf8mb4;

INSERT INTO shop_summary_projection(shop_id,shop_name,owner_user_id,source_version)
VALUES (1,'103茶餐厅',NULL,1)
ON DUPLICATE KEY UPDATE
    shop_name=VALUES(shop_name),
    owner_user_id=VALUES(owner_user_id),
    source_version=GREATEST(source_version,VALUES(source_version));

INSERT INTO commerce_voucher(
    id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status
) VALUES
    (1,1,'50元代金券','周一至周日均可使用','全场通用；无需预约；仅限堂食',4750,5000,0,1),
    (100,1,'压测秒杀券','仅供压测','压测专用',1,100,1,1)
ON DUPLICATE KEY UPDATE
    shop_id=VALUES(shop_id),
    title=VALUES(title),
    sub_title=VALUES(sub_title),
    rules=VALUES(rules),
    pay_value=VALUES(pay_value),
    actual_value=VALUES(actual_value),
    type=VALUES(type),
    status=VALUES(status);

INSERT IGNORE INTO commerce_order(
    id,user_id,voucher_id,shop_id,shop_name_snapshot,
    voucher_title_snapshot,voucher_sub_title_snapshot,voucher_rules_snapshot,
    pay_value_snapshot,actual_value_snapshot,status,version,idempotency_key
) VALUES
    (76638984112964585,100004,100,1,'103茶餐厅','压测秒杀券','仅供压测','压测专用',1,100,1,0,'legacy-76638984112964585'),
    (76638984112964586,100009,100,1,'103茶餐厅','压测秒杀券','仅供压测','压测专用',1,100,1,0,'legacy-76638984112964586'),
    (76638984112964587,100007,100,1,'103茶餐厅','压测秒杀券','仅供压测','压测专用',1,100,1,0,'legacy-76638984112964587'),
    (76638984112964588,100003,100,1,'103茶餐厅','压测秒杀券','仅供压测','压测专用',1,100,1,0,'legacy-76638984112964588'),
    (76638984112964589,100000,100,1,'103茶餐厅','压测秒杀券','仅供压测','压测专用',1,100,1,0,'legacy-76638984112964589'),
    (76638984112964590,100002,100,1,'103茶餐厅','压测秒杀券','仅供压测','压测专用',1,100,1,0,'legacy-76638984112964590'),
    (76638984112964591,100001,100,1,'103茶餐厅','压测秒杀券','仅供压测','压测专用',1,100,1,0,'legacy-76638984112964591'),
    (76638984112964592,100006,100,1,'103茶餐厅','压测秒杀券','仅供压测','压测专用',1,100,1,0,'legacy-76638984112964592'),
    (76638984112964593,100005,100,1,'103茶餐厅','压测秒杀券','仅供压测','压测专用',1,100,1,0,'legacy-76638984112964593'),
    (76638984112964594,100008,100,1,'103茶餐厅','压测秒杀券','仅供压测','压测专用',1,100,1,0,'legacy-76638984112964594');

INSERT IGNORE INTO commerce_reservation(
    order_id,voucher_id,user_id,status,version,stream_record_id
) VALUES
    (76638984112964585,100,100004,'ORDER_CREATED',1,'legacy'),
    (76638984112964586,100,100009,'ORDER_CREATED',1,'legacy'),
    (76638984112964587,100,100007,'ORDER_CREATED',1,'legacy'),
    (76638984112964588,100,100003,'ORDER_CREATED',1,'legacy'),
    (76638984112964589,100,100000,'ORDER_CREATED',1,'legacy'),
    (76638984112964590,100,100002,'ORDER_CREATED',1,'legacy'),
    (76638984112964591,100,100001,'ORDER_CREATED',1,'legacy'),
    (76638984112964592,100,100006,'ORDER_CREATED',1,'legacy'),
    (76638984112964593,100,100005,'ORDER_CREATED',1,'legacy'),
    (76638984112964594,100,100008,'ORDER_CREATED',1,'legacy');
