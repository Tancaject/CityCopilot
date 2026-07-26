package com.citycopilot.core.service;

import com.citycopilot.core.entity.SeckillVoucher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.citycopilot.core.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
public class SeckillInventoryReconciliationService {
    private final ISeckillVoucherService seckillVoucherService;
    private final StringRedisTemplate redisTemplate;
    private final OperationLogService operationLogService;
    private final SeckillConsistencyService consistencyService;
    private final com.citycopilot.core.event.DomainEventService domainEventService;
    private final AtomicInteger currentDiscrepancies = new AtomicInteger();
    private MeterRegistry meterRegistry;

    @Value("${citycopilot.seckill.reconciliation.auto-heal:false}")
    private boolean autoHeal;
    @Value("${citycopilot.commerce.enabled:false}")
    private boolean commerceEnabled;

    public SeckillInventoryReconciliationService(
            ISeckillVoucherService seckillVoucherService,
            StringRedisTemplate redisTemplate,
            OperationLogService operationLogService,
            SeckillConsistencyService consistencyService,
            com.citycopilot.core.event.DomainEventService domainEventService
    ) {
        this.seckillVoucherService = seckillVoucherService;
        this.redisTemplate = redisTemplate;
        this.operationLogService = operationLogService;
        this.consistencyService = consistencyService;
        this.domainEventService = domainEventService;
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        this.meterRegistry = registry;
        Gauge.builder("citycopilot.seckill.stock.discrepancies", currentDiscrepancies, AtomicInteger::get)
                .description("Current Redis and database seckill stock discrepancies")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${citycopilot.seckill.reconciliation.interval-ms:60000}")
    public void reconcile() {
        if (commerceEnabled) {
            return;
        }
        int discrepancies = 0;
        for (SeckillVoucher voucher : seckillVoucherService.list()) {
            if (voucher.getVoucherId() == null || voucher.getStock() == null) {
                continue;
            }
            Map<String, Object> snapshot = consistencyService.snapshot(voucher.getVoucherId());
            if (Boolean.TRUE.equals(snapshot.get("consistent"))) {
                redisTemplate.delete(streakKey(voucher.getVoucherId()));
                continue;
            }
            discrepancies++;
            recordMismatch(voucher, snapshot);
            boolean conflicts = !((java.util.List<?>) snapshot.get("conflicts")).isEmpty();
            if (autoHeal && !conflicts) {
                consistencyService.repairStock(
                        voucher.getVoucherId(),
                        String.valueOf(snapshot.get("evidenceHash"))
                );
            }
            escalatePersistentMismatch(voucher, snapshot);
        }
        currentDiscrepancies.set(discrepancies);
    }

    static boolean isMismatch(int databaseStock, String redisStock) {
        if (redisStock == null) {
            return true;
        }
        try {
            return Integer.parseInt(redisStock) != databaseStock;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private void recordMismatch(SeckillVoucher voucher, Map<String, Object> snapshot) {
        operationLogService.record(
                "seckill",
                "stock_reconcile",
                "seckill_voucher",
                voucher.getVoucherId(),
                String.valueOf(voucher.getVoucherId()),
                snapshot,
                Map.of(
                        "autoHeal", autoHeal,
                        "autoHealBlocked", !((java.util.List<?>) snapshot.get("conflicts")).isEmpty()
                ),
                true,
                null
        );
        if (meterRegistry != null) {
            Counter.builder("citycopilot.seckill.stock.discrepancy.count")
                    .description("Detected Redis and database stock discrepancies")
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void escalatePersistentMismatch(SeckillVoucher voucher, Map<String, Object> snapshot) {
        String streakKey = streakKey(voucher.getVoucherId());
        Long streak = redisTemplate.opsForValue().increment(streakKey);
        redisTemplate.expire(streakKey, Duration.ofHours(1));
        if (streak == null || streak < 2) {
            return;
        }
        String incidentKey = "seckill-stock:" + voucher.getVoucherId();
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                "event:stock-discrepancy:cooldown:" + voucher.getVoucherId(),
                incidentKey,
                Duration.ofMinutes(5)
        );
        if (!Boolean.TRUE.equals(claimed)) {
            return;
        }
        domainEventService.appendStockDiscrepancyDetected(
                voucher.getVoucherId(),
                ((Number) snapshot.get("databaseStock")).intValue(),
                snapshot.get("redisStock") instanceof Number number ? number.intValue() : Integer.MIN_VALUE,
                ((Number) snapshot.get("consumingReservations")).intValue(),
                incidentKey
        );
    }

    private String streakKey(Long voucherId) {
        return "metrics:seckill:discrepancy-streak:" + voucherId;
    }
}
