-- 店铺销量投影：每张订单在 Core 侧最后看到的状态。按状态而不是按增量计数，乱序和退款都能算对：
-- 只有已支付(2)、已使用(3)计入 shop.sold，新事件版本高于已存版本才覆盖。
CREATE TABLE shop_order_sales (
    order_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    status TINYINT NOT NULL,
    aggregate_version BIGINT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_id),
    KEY idx_shop_order_sales_shop (shop_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
