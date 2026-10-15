package com.citycopilot.contract.event;

import java.time.Instant;

/**
 * Commerce 成功创建订单后发布的跨服务领域事件。
 *
 * @param orderId 订单主键
 * @param userId 下单用户主键
 * @param voucherId 关联优惠券主键
 * @param status 发布事件时的订单状态码
 * @param version 订单聚合版本，用于消费端识别乱序事件
 * @param createdAt 订单创建时间
 * @param shopId 订单所属店铺主键（schemaVersion 2 起），供 Core 维护店铺销量
 */
public record OrderCreatedEvent(
        long orderId,
        long userId,
        long voucherId,
        int status,
        int version,
        Instant createdAt,
        long shopId
) {
    /** 事件信封与 Kafka 消息头中使用的稳定事件类型。 */
    public static final String EVENT_TYPE = "commerce.order.created";
    /** 当前事件载荷结构版本；2 在 1 的基础上只新增 shopId，旧消费者忽略即可。 */
    public static final int SCHEMA_VERSION = 2;
}
