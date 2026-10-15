package com.citycopilot.contract.event;

import java.time.Instant;

/**
 * 库存对账发现 MySQL、Redis 与有效预留不一致时发布的告警事件。
 *
 * @param voucherId 出现差异的优惠券主键
 * @param databaseStock MySQL 中的可售库存
 * @param redisStock Redis 准入库存
 * @param activeReservations 尚未完结的库存预留数
 * @param incidentKey 本次差异的稳定标识，用于消费端去重
 * @param detectedAt 发现差异的时间
 */
public record StockDiscrepancyDetectedEvent(
        long voucherId,
        int databaseStock,
        int redisStock,
        int activeReservations,
        String incidentKey,
        Instant detectedAt
) {
    /** 事件信封与 Kafka 消息头中使用的稳定事件类型。 */
    public static final String EVENT_TYPE = "commerce.stock.discrepancy-detected";
    /** 当前事件载荷结构版本。 */
    public static final int SCHEMA_VERSION = 1;
}
