package com.citycopilot.commerce;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 仅为从未发生准入的 Voucher 初始化 Redis 库存。
 *
 * <p>若已有业务历史的 Redis 状态丢失，本组件故意保持库存键缺失，使准入失败关闭；
 * 库存只能由带证据哈希的一致性修复流程重建，不能用数据库值盲目覆盖。</p>
 */
@Component
final class CommerceRedisInventoryBootstrap {
    private static final Logger log = LoggerFactory.getLogger(CommerceRedisInventoryBootstrap.class);
    private static final int SCAN_BATCH_SIZE = 200;

    /** 查询数据库库存及该 Voucher 的历史处理痕迹。 */
    private final JdbcTemplate jdbc;
    /** 读取并以 SET NX 初始化 Redis 库存。 */
    private final StringRedisTemplate redis;
    /** 生成当前路由代际和分片对应的 Redis 键。 */
    private final CommerceRedisKeyspace keyspace;
    /** 是否在启动时执行新库存初始化。 */
    private final boolean bootstrapEnabled;

    @Autowired
    CommerceRedisInventoryBootstrap(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            CommerceRedisKeyspace keyspace,
            @Value("${citycopilot.commerce.inventory-bootstrap-enabled:true}") boolean bootstrapEnabled
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.keyspace = keyspace;
        this.bootstrapEnabled = bootstrapEnabled;
    }

    CommerceRedisInventoryBootstrap(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this(jdbc, redis, new CommerceRedisKeyspace(1, "0"), true);
    }

    /** 启动后扫描所有 Voucher，只初始化确认没有订单、预留、恢复记录的库存。 */
    @PostConstruct
    void initializeFreshInventory() {
        if (!bootstrapEnabled) return;
        Long highWatermark = jdbc.queryForObject("SELECT MAX(voucher_id) FROM commerce_seckill_voucher", Long.class);
        if (highWatermark == null) return;
        long afterId = 0;
        // 固定本轮上界，启动过程中新增的券由提交后的初始化处理，不让扫描不断追赶新增记录。
        while (afterId < highWatermark) {
            List<Map<String, Object>> vouchers = jdbc.queryForList("""
                    SELECT s.voucher_id,s.stock,
                           EXISTS(SELECT 1 FROM commerce_order o
                                  WHERE o.voucher_id=s.voucher_id) AS order_count,
                           EXISTS(SELECT 1 FROM commerce_reservation r
                                  WHERE r.voucher_id=s.voucher_id) AS reservation_count,
                           EXISTS(SELECT 1 FROM commerce_order_recovery recovery
                                  WHERE recovery.voucher_id=s.voucher_id) AS recovery_count
                    FROM commerce_seckill_voucher s
                    WHERE s.voucher_id>? AND s.voucher_id<=?
                    ORDER BY s.voucher_id
                    LIMIT ?
                    """, afterId, highWatermark, SCAN_BATCH_SIZE);
            if (vouchers.isEmpty()) break;
            for (Map<String, Object> voucher : vouchers) {
                long voucherId = ((Number) voucher.get("voucher_id")).longValue();
                int stock = ((Number) voucher.get("stock")).intValue();
                long orderCount = ((Number) voucher.get("order_count")).longValue();
                long reservationCount = ((Number) voucher.get("reservation_count")).longValue();
                long recoveryCount = ((Number) voucher.get("recovery_count")).longValue();
                initializeIfFresh(voucherId, stock, orderCount, reservationCount, recoveryCount);
                afterId = voucherId;
            }
        }
    }

    /**
     * 在数据库无历史、Redis 无在途准入且库存键不存在时原子初始化库存。
     *
     * @param voucherId Voucher ID
     * @param stock 数据库中的初始库存
     * @param orderCount 已落库订单数
     * @param reservationCount 预留记录数
     * @param recoveryCount 恢复记录数
     * @return 本次是否成功创建库存键
     */
    boolean initializeIfFresh(
            long voucherId,
            int stock,
            long orderCount,
            long reservationCount,
            long recoveryCount
    ) {
        String key = keyspace.stock(voucherId);
        if (redis.opsForValue().get(key) != null) {
            return false;
        }
        Long inFlight = redis.opsForSet().size(keyspace.inflight(voucherId));
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
        // SET NX 避免启动中的并发实例相互覆盖已初始化或刚被准入扣减的库存。
        if (Boolean.TRUE.equals(initialized)) {
            log.info("Initialized fresh Commerce Redis inventory for voucher {}", voucherId);
            return true;
        }
        return false;
    }

    /**
     * 数据库创建 Voucher 后尽力初始化 Redis；失败时保持准入关闭并交给修复流程。
     *
     * @param voucherId 新 Voucher ID
     * @param stock 初始库存
     */
    void initializeNewVoucher(long voucherId, int stock) {
        try {
            initializeIfFresh(voucherId, stock, 0, 0, 0);
        } catch (RuntimeException error) {
            // 数据库事务此时已经提交，不能回滚；保留缺失库存键，让准入失败关闭并等待证据化修复。
            log.error(
                    "Unable to initialize Redis inventory for new voucher {}; "
                            + "seckill admission remains closed",
                    voucherId,
                    error
            );
        }
    }
}
