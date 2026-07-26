package com.citycopilot.core.event;

import com.citycopilot.contract.event.DomainEventEnvelope;
import com.citycopilot.contract.event.OrderCreatedEvent;
import com.citycopilot.contract.event.StockDiscrepancyDetectedEvent;
import com.citycopilot.core.entity.VoucherOrder;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class DomainEventService {
    public static final String COMMERCE_TOPIC = "citycopilot-commerce-events-v1";
    public static final String OPS_TOPIC = "citycopilot-ops-events-v1";

    private final OutboxRepository outboxRepository;

    public DomainEventService(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void appendOrderCreated(VoucherOrder order) {
        String eventId = UUID.randomUUID().toString();
        String traceId = MDC.get("traceId");
        String correlationId = MDC.get("correlationId");
        OrderCreatedEvent payload = new OrderCreatedEvent(
                order.getId(),
                order.getUserId(),
                order.getVoucherId(),
                order.getStatus(),
                order.getVersion() == null ? 0 : order.getVersion(),
                order.getCreateTime().toInstant(ZoneOffset.UTC)
        );
        DomainEventEnvelope<OrderCreatedEvent> envelope = new DomainEventEnvelope<>(
                eventId,
                OrderCreatedEvent.EVENT_TYPE,
                OrderCreatedEvent.SCHEMA_VERSION,
                "coupon_order",
                String.valueOf(order.getId()),
                payload.version(),
                "core-service",
                Instant.now(),
                traceId,
                correlationId == null ? String.valueOf(order.getId()) : correlationId,
                MDC.get("causationId"),
                payload
        );
        outboxRepository.append(envelope, COMMERCE_TOPIC, "OrderCreated");
    }

    public void appendStockDiscrepancyDetected(
            long voucherId,
            int databaseStock,
            int redisStock,
            int activeReservations,
            String incidentKey
    ) {
        String eventId = UUID.randomUUID().toString();
        StockDiscrepancyDetectedEvent payload = new StockDiscrepancyDetectedEvent(
                voucherId,
                databaseStock,
                redisStock,
                activeReservations,
                incidentKey,
                Instant.now()
        );
        DomainEventEnvelope<StockDiscrepancyDetectedEvent> envelope = new DomainEventEnvelope<>(
                eventId,
                StockDiscrepancyDetectedEvent.EVENT_TYPE,
                StockDiscrepancyDetectedEvent.SCHEMA_VERSION,
                "seckill_voucher",
                String.valueOf(voucherId),
                System.currentTimeMillis(),
                "core-service",
                payload.detectedAt(),
                MDC.get("traceId"),
                incidentKey,
                MDC.get("causationId"),
                payload
        );
        outboxRepository.append(envelope, OPS_TOPIC, "StockDiscrepancyDetected");
    }
}
