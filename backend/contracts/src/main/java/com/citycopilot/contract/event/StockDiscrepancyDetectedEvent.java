package com.citycopilot.contract.event;

import java.time.Instant;

public record StockDiscrepancyDetectedEvent(
        long voucherId,
        int databaseStock,
        int redisStock,
        int activeReservations,
        String incidentKey,
        Instant detectedAt
) {
    public static final String EVENT_TYPE = "commerce.stock.discrepancy-detected";
    public static final int SCHEMA_VERSION = 1;
}
