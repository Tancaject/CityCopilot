package com.citycopilot.commerce;

import com.citycopilot.contract.event.EventEnvelope;
import com.citycopilot.contract.event.OrderCreatedEvent;
import com.citycopilot.contract.commerce.CommerceContracts.OrderSummary;
import com.citycopilot.contract.commerce.CommerceContracts.OrderView;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_ORDER_CREATED;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_RECEIVED;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_REPAIR_REQUIRED;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_RESTORED;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_RESTORE_PENDING;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.LostRecoveryClaim;

/** 订单查询、原子落单与状态流转；订单、准入账本、Reservation 和 Outbox 共用事务。 */
@Service
public class CommerceOrderService {
    private static final Logger log = LoggerFactory.getLogger(CommerceOrderService.class);
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final CommerceAdmissionDurabilityService durableAdmissions;
    private final CommerceRedisKeyspace keyspace;
    private final CommerceRuntimeMutationLeases runtimeLeases;
    private final CommerceRoutingGuard routing;
    private final CommerceOrderRecoveryStore recovery;
    private static final DefaultRedisScript<Long> RESTORE_INVENTORY_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_restore_inventory.lua");
    private static final DefaultRedisScript<Long> RELEASE_FENCE_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_release_fence.lua");
    private static final Duration INVENTORY_FENCE_TTL = Duration.ofSeconds(30);

    public CommerceOrderService(JdbcTemplate jdbc, StringRedisTemplate redis, TransactionTemplate transactions,
            ObjectMapper objectMapper, CommerceAdmissionDurabilityService durableAdmissions,
            CommerceRedisKeyspace keyspace, CommerceRuntimeMutationLeases runtimeLeases,
            CommerceRoutingGuard routing, CommerceOrderRecoveryStore recovery) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.durableAdmissions = durableAdmissions;
        this.keyspace = keyspace;
        this.runtimeLeases = runtimeLeases;
        this.routing = routing;
        this.recovery = recovery;
    }

    /**
     * 在一个 MySQL 事务中完成预留、条件扣库存、订单、Outbox、准入账本和 Recovery 终态。
     *
     * <p>数据库条件更新和唯一约束是最终正确性边界；Redis Lua 只负责高并发前置准入。</p>
     */
    void createOrder(
            String sourceId,
            String claimToken,
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey
    ) {
        transactions.executeWithoutResult(status -> {
            recovery.requireOwnedPhase(sourceId, claimToken, PHASE_RECEIVED);
            // Reservation 先记录 Redis 已接受的库存意图，失败恢复时可判断库存是否正在被消费。
            jdbc.update("""
                    INSERT INTO commerce_reservation(
                        order_id,voucher_id,user_id,status,stream_record_id
                    ) VALUES (?,?,?,'RESERVED',?)
                    """, orderId, voucherId, userId, sourceId);
            // stock>0 条件更新是 MySQL 最终防超卖边界；不依赖应用层锁。
            Integer updated = jdbc.update("""
                    UPDATE commerce_seckill_voucher
                    SET stock=stock-1, version=version+1
                    WHERE voucher_id=? AND stock>0
                    """, voucherId);
            if (updated != 1) {
                throw new CommerceApiException(
                        "COMMERCE_DATABASE_STOCK_EXHAUSTED",
                        "Database stock exhausted after Redis admission",
                        409
                );
            }
            Map<String, Object> voucher = jdbc.queryForMap("""
                    SELECT v.id, v.shop_id, v.title, v.sub_title, v.rules,
                           v.pay_value, v.actual_value, p.shop_name
                    FROM commerce_voucher v
                    LEFT JOIN shop_summary_projection p ON p.shop_id=v.shop_id
                    WHERE v.id=?
                    """, voucherId);
            // 订单与事件同事务写入；RocketMQ 不可用时 Outbox Relay 可稍后重发。
            jdbc.update("""
                    INSERT INTO commerce_order(
                        id,user_id,voucher_id,shop_id,shop_name_snapshot,
                        voucher_title_snapshot,voucher_sub_title_snapshot,voucher_rules_snapshot,
                        pay_value_snapshot,actual_value_snapshot,status,version,idempotency_key
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,1,0,?)
                    """,
                    orderId, userId, voucherId, voucher.get("shop_id"),
                    value(voucher.get("shop_name")), value(voucher.get("title")),
                    value(voucher.get("sub_title")), value(voucher.get("rules")),
                    voucher.get("pay_value"), voucher.get("actual_value"), idempotencyKey
            );
            jdbc.update("""
                    UPDATE commerce_reservation
                    SET status='ORDER_CREATED', version=version+1
                    WHERE order_id=? AND status='RESERVED'
                    """, orderId);
            Instant occurredAt = Instant.now();
            OrderCreatedEvent payload = new OrderCreatedEvent(
                    orderId,
                    userId,
                    voucherId,
                    1,
                    0,
                    occurredAt
            );
            EventEnvelope<OrderCreatedEvent> event = new EventEnvelope<>(
                    UUID.randomUUID().toString(), OrderCreatedEvent.EVENT_TYPE, OrderCreatedEvent.SCHEMA_VERSION,
                    "commerce_order", String.valueOf(orderId), 0, "commerce-service",
                    occurredAt.toString(), String.valueOf(orderId), payload);
            jdbc.update("""
                    INSERT INTO commerce_outbox_event(
                        event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                        topic,payload_json,status
                    ) VALUES (?,?,?,?,?,'citycopilot-commerce-events-v1',?,'READY')
                    """,
                    event.eventId(), event.eventType(), event.aggregateType(),
                    event.aggregateId(), event.aggregateVersion(), json(event)
            );
            durableAdmissions.markOrderCreated(orderId, voucherId, userId, idempotencyKey);
            int terminal = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, terminal_at=CURRENT_TIMESTAMP(3),
                        claim_token=NULL, claim_owner=NULL, claimed_until=NULL
                    WHERE source_id=? AND claim_token=? AND phase=?
                    """, PHASE_ORDER_CREATED, sourceId, claimToken, PHASE_RECEIVED);
            if (terminal != 1) throw new LostRecoveryClaim();
        });
    }

    /**
     * 查询订单快照；非管理员查询自动附加 user_id 条件，避免越权枚举。
     *
     * @param orderId 订单 ID
     * @param userId 当前用户 ID
     * @param admin 是否使用管理员范围
     * @return 规范化订单字段
     */
    public OrderView order(long orderId, long userId, boolean admin) {
        return order(orderId, userId, admin, null);
    }

    private OrderView order(long orderId, long userId, boolean admin, String inventoryRecovery) {
        String sql = """
                SELECT id,user_id,voucher_id,shop_id,shop_name_snapshot,
                       voucher_title_snapshot,voucher_sub_title_snapshot,voucher_rules_snapshot,
                       pay_value_snapshot,actual_value_snapshot,pay_type,status,version,
                       create_time,pay_time,use_time,refund_time,update_time
                FROM commerce_order WHERE id=?
                """ + (admin ? "" : " AND user_id=?");
        List<OrderView> rows = jdbc.query(sql, (row, index) -> {
            int status = row.getInt("status");
            OrderStatus presentation = orderStatus(status);
            return new OrderView(
                    row.getString("id"), row.getLong("user_id"), row.getLong("voucher_id"), row.getLong("shop_id"),
                    row.getString("shop_name_snapshot"), row.getString("voucher_title_snapshot"),
                    row.getString("voucher_sub_title_snapshot"), row.getString("voucher_rules_snapshot"),
                    row.getInt("pay_value_snapshot"), row.getInt("actual_value_snapshot"), row.getObject("pay_type", Integer.class),
                    status, presentation.state(), presentation.statusText(), row.getInt("version"), row.getTimestamp("create_time"),
                    row.getTimestamp("pay_time"), row.getTimestamp("use_time"), row.getTimestamp("refund_time"),
                    row.getTimestamp("update_time"), inventoryRecovery);
        },
                admin ? new Object[]{orderId} : new Object[]{orderId, userId});
        if (rows.isEmpty()) {
            throw new CommerceApiException("COMMERCE_ORDER_NOT_FOUND", "Order not found", 404);
        }
        return rows.get(0);
    }

    /** 按订单 ID 倒序查询用户订单，每页 10 条且页码小于 1 时按第一页处理。 */
    public List<OrderSummary> myOrders(long userId, int page) {
        return jdbc.query("""
                SELECT id,user_id,voucher_id,shop_id,shop_name_snapshot,
                       voucher_title_snapshot,voucher_sub_title_snapshot,voucher_rules_snapshot,
                       pay_value_snapshot,actual_value_snapshot,status,version,create_time,update_time
                FROM commerce_order
                WHERE user_id=?
                ORDER BY id DESC
                LIMIT 10 OFFSET ?
                """, (row, index) -> {
            int status = row.getInt("status");
            OrderStatus presentation = orderStatus(status);
            return new OrderSummary(
                    row.getString("id"), row.getLong("user_id"), row.getLong("voucher_id"), row.getLong("shop_id"),
                    row.getString("shop_name_snapshot"), row.getString("voucher_title_snapshot"),
                    row.getString("voucher_sub_title_snapshot"), row.getString("voucher_rules_snapshot"),
                    row.getInt("pay_value_snapshot"), row.getInt("actual_value_snapshot"),
                    status, presentation.state(), presentation.statusText(), row.getInt("version"),
                    row.getTimestamp("create_time"), row.getTimestamp("update_time"));
        }, userId, Math.max(0, page - 1) * 10);
    }

    /** 已落库订单的状态展示由同一事实生成，列表、详情和动作结果共用此映射。 */
    private static OrderStatus orderStatus(int status) {
        return switch (status) {
            case 1 -> new OrderStatus("unpaid", "待支付");
            case 2 -> new OrderStatus("paid", "已支付");
            case 3 -> new OrderStatus("used", "已核销");
            case 4 -> new OrderStatus("cancelled", "已取消");
            case 5 -> new OrderStatus("refunding", "退款中");
            case 6 -> new OrderStatus("refunded", "已退款");
            default -> new OrderStatus("unknown", "状态未知");
        };
    }

    private record OrderStatus(String state, String statusText) {}

    /**
     * 执行订单状态流转；取消/退款还会在 fence 保护下恢复 MySQL 与 Redis 库存。
     *
     * @param orderId 订单 ID
     * @param userId 操作者用户 ID
     * @param action pay、cancel、use 或 refund
     * @param payType 支付类型，非 pay 动作忽略
     * @param admin 是否以管理员范围操作
     * @return 流转后的订单及可能的库存恢复状态
     */
    public OrderView transition(long orderId, long userId, String action, Integer payType, boolean admin) {
        if (runtimeLeases == null) {
            return transitionWithLease(orderId, userId, action, payType, admin, null);
        }
        try (CommerceRuntimeMutationLeases.MutationLease lease = runtimeLeases.acquireMutation()) {
            routing.requireRuntimeMutationActive();
            OrderView result = transitionWithLease(
                    orderId, userId, action, payType, admin, lease
            );
            lease.renewAndAssert();
            return result;
        }
    }

    /**
     * 在运行时写租约内以数据库状态条件更新推进订单，并为返库存动作持久化恢复意图。
     */
    private OrderView transitionWithLease(
            long orderId,
            long userId,
            String action,
            Integer payType,
            boolean admin,
            CommerceRuntimeMutationLeases.MutationLease lease
    ) {
        Map<String, Transition> transitions = Map.of(
                "pay", new Transition(1, 2),
                "cancel", new Transition(1, 4),
                "use", new Transition(2, 3),
                "refund", new Transition(2, 6)
        );
        Transition transition = transitions.get(action);
        if (transition == null) {
            throw new CommerceApiException("COMMERCE_ACTION_UNSUPPORTED", "Unsupported order action", 400);
        }
        String ownerClause = admin ? "" : " AND user_id=?";
        String timeColumn = switch (action) {
            case "pay" -> "pay_time";
            case "use" -> "use_time";
            case "refund" -> "refund_time";
            default -> "update_time";
        };
        String sql = "UPDATE commerce_order SET status=?, version=version+1, "
                + timeColumn + "=CURRENT_TIMESTAMP(3)"
                + ("pay".equals(action) ? ", pay_type=?" : "")
                + " WHERE id=? AND status=?" + ownerClause;
        OrderView before = order(orderId, userId, admin);
        long voucherId = before.voucherId();
        boolean returnsInventory = "cancel".equals(action) || "refund".equals(action);
        String fenceToken = returnsInventory ? acquireInventoryFence(voucherId) : null;
        String recoverySourceId = returnsInventory
                ? "transition:" + orderId + ":" + action + ":" + before.version()
                : null;
        boolean transitionCommitted = false;
        try {
            Boolean changed = transactions.execute(status -> {
                assertLease(lease);
                int updated;
                if ("pay".equals(action)) {
                    updated = admin
                            ? jdbc.update(sql, transition.to(), payType == null ? 1 : payType, orderId, transition.from())
                            : jdbc.update(sql, transition.to(), payType == null ? 1 : payType, orderId, transition.from(), userId);
                } else {
                    updated = admin
                            ? jdbc.update(sql, transition.to(), orderId, transition.from())
                            : jdbc.update(sql, transition.to(), orderId, transition.from(), userId);
                }
                if (updated != 1) return false;
                if (returnsInventory) {
                    jdbc.update("""
                            UPDATE commerce_seckill_voucher
                            SET stock=stock+1, version=version+1
                            WHERE voucher_id=?
                            """, voucherId);
                    jdbc.update("""
                            INSERT INTO commerce_order_recovery(
                                source_id,source_type,order_id,voucher_id,user_id,payload_json,
                                phase,inventory_fence_token,compensation_delta
                            ) VALUES (?,'STATE_TRANSITION',?,?,?,?,?,?,1)
                            """,
                            recoverySourceId, orderId, voucherId, userId,
                            json(Map.of("action", action, "targetStatus", transition.to())),
                            PHASE_RESTORE_PENDING, fenceToken
                    );
                }
                appendStateEvent(orderId, userId, action, transition.to());
                // TransactionTemplate 在回调返回后才提交；提交前续租失败会回滚本次全部 SQL 变更。
                assertLease(lease);
                return true;
            });
            if (!Boolean.TRUE.equals(changed)) {
                throw new CommerceApiException(
                        "COMMERCE_ORDER_STATE_CONFLICT",
                        "Order state changed or action is not allowed",
                        409
                );
            }
            transitionCommitted = true;
            String inventoryRecovery = null;
            if (returnsInventory) {
                inventoryRecovery = attemptTransitionInventoryRestore(
                        recoverySourceId,
                        orderId,
                        voucherId,
                        fenceToken,
                        lease
                );
            }
            return order(orderId, userId, admin,
                    PHASE_RESTORED.equals(inventoryRecovery) ? null : inventoryRecovery);
        } finally {
            if (fenceToken != null && !transitionCommitted) {
                releaseInventoryFence(voucherId, fenceToken);
            }
        }
    }

    private void assertLease(CommerceRuntimeMutationLeases.MutationLease lease) {
        if (lease != null) lease.renewAndAssert();
    }

    /** 测试/直接调用入口：不带运行时租约尝试一次库存恢复。 */
    String attemptTransitionInventoryRestore(
            String sourceId,
            long orderId,
            long voucherId,
            String fenceToken
    ) {
        return attemptTransitionInventoryRestore(
                sourceId, orderId, voucherId, fenceToken, null
        );
    }

    /**
     * 用 fence token 和幂等 marker 恢复 Redis 库存，再把 Recovery 行标为 RESTORED。
     *
     * @return RESTORED、RESTORE_PENDING 或 REPAIR_REQUIRED
     */
    String attemptTransitionInventoryRestore(
            String sourceId,
            long orderId,
            long voucherId,
            String fenceToken,
            CommerceRuntimeMutationLeases.MutationLease lease
    ) {
        Long restored;
        try {
            assertLease(lease);
            restored = redis.execute(
                    RESTORE_INVENTORY_SCRIPT,
                    List.of(
                            keyspace.fence(voucherId),
                            keyspace.stock(voucherId),
                            keyspace.restoreMarker(voucherId, sourceId)
                    ),
                    fenceToken
            );
        } catch (Exception unavailable) {
            // 保持 RESTORE_PENDING；Lua marker 能区分“已增加库存”和“尚未执行”，调度器可安全重试。
            log.warn("Inventory restore remains pending for order {}", orderId);
            return PHASE_RESTORE_PENDING;
        }
        if (restored != null && (restored == 1L || restored == 2L)) {
            assertLease(lease);
            int completed = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, compensation_delta=0, terminal_at=CURRENT_TIMESTAMP(3)
                    WHERE source_id=? AND phase=?
                    """, PHASE_RESTORED, sourceId, PHASE_RESTORE_PENDING);
            if (completed == 1) {
                try {
                    redis.delete(keyspace.restoreMarker(voucherId, sourceId));
                } catch (Exception cleanupFailure) {
                    log.debug("Unable to remove completed inventory restore marker {}", sourceId);
                }
            }
            assertLease(lease);
            return PHASE_RESTORED;
        }
        String reason = restored != null && restored == -2L
                 ? "redis_stock_missing_during_transition_restore"
                : "inventory_fence_expired_before_transition_restore";
        assertLease(lease);
        jdbc.update("""
                UPDATE commerce_order_recovery
                SET phase=?, compensation_delta=1, last_error=?,
                    terminal_at=CURRENT_TIMESTAMP(3)
                WHERE source_id=? AND phase=?
                """, PHASE_REPAIR_REQUIRED, reason, sourceId, PHASE_RESTORE_PENDING);
        assertLease(lease);
        releaseInventoryFence(voucherId, fenceToken);
        return PHASE_REPAIR_REQUIRED;
    }

    /** 在订单状态事务内追加 Outbox 事件，交由 Relay 异步发布。 */
    private void appendStateEvent(long orderId, long userId, String action, int state) {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope<Map<String, Object>> event = new EventEnvelope<>(
                eventId, "commerce.order." + action, 1, "commerce_order", String.valueOf(orderId), state,
                "commerce-service", Instant.now().toString(), String.valueOf(orderId), Map.of(
                "orderId", orderId,
                "userId", userId,
                "action", action,
                "status", state
        ));
        jdbc.update("""
                INSERT INTO commerce_outbox_event(
                    event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                    topic,payload_json,status
                ) VALUES (?,?,?,?,?,'citycopilot-commerce-events-v1',?,'READY')
                """,
                eventId,
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateVersion(),
                json(event)
        );
    }

    /**
     * 在返库存期间建立短期 fence，阻止准入 Lua 观察 MySQL/Redis 库存不同步窗口。
     */
    private String acquireInventoryFence(long voucherId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(
                keyspace.fence(voucherId),
                token,
                INVENTORY_FENCE_TTL
        );
        if (!Boolean.TRUE.equals(acquired)) {
            throw new CommerceApiException(
                    "COMMERCE_INVENTORY_MAINTENANCE",
                    "Voucher inventory is under maintenance",
                    409
            );
        }
        return token;
    }

    /** 仅以令牌匹配释放库存 fence；失败时依靠 TTL 最终解除。 */
    private void releaseInventoryFence(long voucherId, String token) {
        try {
            redis.execute(
                    RELEASE_FENCE_SCRIPT,
                    List.of(keyspace.fence(voucherId)),
                    token
            );
        } catch (Exception failure) {
            log.warn(
                    "Unable to release inventory fence for voucher {}; TTL recovery remains active",
                    voucherId
            );
        }
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize commerce payload", error);
        }
    }

    private record Transition(int from, int to) {}
}
