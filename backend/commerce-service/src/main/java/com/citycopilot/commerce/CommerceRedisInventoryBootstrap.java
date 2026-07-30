package com.citycopilot.commerce;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Initializes Redis inventory only for vouchers that have never admitted an order.
 *
 * <p>If Redis state disappears after Commerce has processed traffic, the service
 * intentionally leaves the key absent. Admission then fails closed until the
 * evidence-based consistency repair endpoint rebuilds the stock.</p>
 */
@Component
final class CommerceRedisInventoryBootstrap {
    private static final Logger log = LoggerFactory.getLogger(CommerceRedisInventoryBootstrap.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    CommerceRedisInventoryBootstrap(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @PostConstruct
    void initializeFreshInventory() {
        List<Map<String, Object>> vouchers = jdbc.queryForList("""
                SELECT s.voucher_id,s.stock,
                       (SELECT COUNT(*) FROM commerce_order o
                        WHERE o.voucher_id=s.voucher_id) AS order_count,
                       (SELECT COUNT(*) FROM commerce_reservation r
                        WHERE r.voucher_id=s.voucher_id) AS reservation_count,
                       (SELECT COUNT(*) FROM commerce_order_recovery recovery
                        WHERE recovery.voucher_id=s.voucher_id) AS recovery_count
                FROM commerce_seckill_voucher s
                """);
        for (Map<String, Object> voucher : vouchers) {
            long voucherId = ((Number) voucher.get("voucher_id")).longValue();
            int stock = ((Number) voucher.get("stock")).intValue();
            long orderCount = ((Number) voucher.get("order_count")).longValue();
            long reservationCount = ((Number) voucher.get("reservation_count")).longValue();
            long recoveryCount = ((Number) voucher.get("recovery_count")).longValue();
            initializeIfFresh(voucherId, stock, orderCount, reservationCount, recoveryCount);
        }
    }

    boolean initializeIfFresh(
            long voucherId,
            int stock,
            long orderCount,
            long reservationCount,
            long recoveryCount
    ) {
        String key = CommerceOrderService.STOCK_PREFIX + voucherId;
        if (redis.opsForValue().get(key) != null) {
            return false;
        }
        Long inFlight = redis.opsForSet().size(CommerceOrderService.inflightKey(voucherId));
        if (orderCount != 0
                || reservationCount != 0
                || recoveryCount != 0
                || inFlight == null
                || inFlight != 0L) {
            log.warn(
                    "Redis stock is missing for voucher {} with Commerce history or in-flight admission; "
                            + "leaving admission closed until consistency repair",
                    voucherId
            );
            return false;
        }
        Boolean initialized = redis.opsForValue().setIfAbsent(key, String.valueOf(stock));
        if (Boolean.TRUE.equals(initialized)) {
            log.info("Initialized fresh Commerce Redis inventory for voucher {}", voucherId);
            return true;
        }
        return false;
    }

    void initializeNewVoucher(long voucherId, int stock) {
        try {
            initializeIfFresh(voucherId, stock, 0, 0, 0);
        } catch (RuntimeException error) {
            // The database transaction has already committed. Keep admission
            // fail-closed and let the evidence-based repair workflow recover.
            log.error(
                    "Unable to initialize Redis inventory for new voucher {}; "
                            + "seckill admission remains closed",
                    voucherId,
                    error
            );
        }
    }
}
