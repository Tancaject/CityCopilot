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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final CommerceOrderRecoveryStore recovery;
    /** 商家视角下按订单所属店铺校验归属。 */
    private final PlaceAuthorityClient place;
    private static final DefaultRedisScript<Long> RESTORE_INVENTORY_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_restore_inventory.lua");
    private static final DefaultRedisScript<Long> RESTORE_INVENTORY_BATCH_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_restore_inventory_batch.lua");
    private static final DefaultRedisScript<Long> RELEASE_FENCE_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_release_fence.lua");
    private static final Duration INVENTORY_FENCE_TTL = Duration.ofSeconds(30);
    /** 单轮过期处理的时间预算；调度线程池只有几个线程且由多个任务共享，不能长时间独占一个。 */
    private static final long EXPIRY_BUDGET_NANOS = Duration.ofSeconds(2).toNanos();
    /** 每批最多关闭的同券到期订单数；批越大提交越少，但持有券库存行锁越久。 */
    private static final int EXPIRY_BATCH_SIZE = 100;

    public CommerceOrderService(JdbcTemplate jdbc, StringRedisTemplate redis, TransactionTemplate transactions,
            ObjectMapper objectMapper, CommerceAdmissionDurabilityService durableAdmissions,
            CommerceRedisKeyspace keyspace, CommerceOrderRecoveryStore recovery, PlaceAuthorityClient place) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.durableAdmissions = durableAdmissions;
        this.keyspace = keyspace;
        this.recovery = recovery;
        this.place = place;
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
            // 订单与事件同事务写入；Kafka 不可用时 Outbox Relay 可稍后重发。
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
            EventEnvelope<OrderCreatedEvent> event = orderCreatedEvent(orderId, userId, voucherId,
                    ((Number) voucher.get("shop_id")).longValue());
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

    private EventEnvelope<OrderCreatedEvent> orderCreatedEvent(long orderId, long userId, long voucherId, long shopId) {
        Instant occurredAt = Instant.now();
        OrderCreatedEvent payload = new OrderCreatedEvent(orderId, userId, voucherId, 1, 0, occurredAt, shopId);
        return new EventEnvelope<>(
                UUID.randomUUID().toString(), OrderCreatedEvent.EVENT_TYPE, OrderCreatedEvent.SCHEMA_VERSION,
                "commerce_order", String.valueOf(orderId), 0, "commerce-service",
                occurredAt.toString(), String.valueOf(orderId), payload);
    }

    /** 批量建单的一条 Stream 订单消息。 */
    record BatchOrder(String sourceId, String recordId, long orderId, long voucherId, long userId,
                      String idempotencyKey, String payloadJson) {}

    /**
     * 批量建单结果。
     *
     * @param committed 本事务实际建单并终态化的订单，只有它们可以做 Redis 收尾
     * @param skipped 未取得批量资格的订单（已认领、已终态、身份不符、账本不在 PREPARED/ACCEPTED），交逐条路径
     * @param acceptedTransitions 账本中由 ACCEPTED 转出的数量，提交后用于扣减积压计数
     */
    record BatchResult(List<BatchOrder> committed, List<BatchOrder> skipped, int acceptedTransitions) {}

    /** 批量事务因业务事实不满足批量前提而整体回滚；数据库确定未提交，可改走逐条路径。 */
    static final class BatchRejected extends RuntimeException {
        BatchRejected(String message) {
            super(message);
        }
    }

    /**
     * 在一个 MySQL 事务中为同一 Voucher 的一批订单完成与 {@link #createOrder} 相同的全部写入。
     *
     * <p>热点库存行只锁一次、扣 N 份，整批只提交一次。Recovery 行用 {@code FOR UPDATE} 锁定代替逐条
     * claim：锁定、身份核对、业务写入和终态化都在同一事务内完成。任何一步的结果与批量前提不符都抛出
     * {@link BatchRejected} 回滚整批，由调用方在事务外改走逐条路径。</p>
     *
     * @param voucherId 本批共同的券
     * @param batch 同券订单，调用方保证不超过单批上限
     */
    BatchResult createOrders(long voucherId, List<BatchOrder> batch) {
        return transactions.execute(status -> {
            List<Object> recoveryRows = new ArrayList<>();
            for (BatchOrder order : batch) {
                recoveryRows.addAll(List.of(order.sourceId(), order.orderId(), voucherId, order.userId(),
                        order.idempotencyKey(), order.payloadJson(), PHASE_RECEIVED));
            }
            jdbc.update("""
                    INSERT IGNORE INTO commerce_order_recovery(
                        source_id,source_type,order_id,voucher_id,user_id,idempotency_key,payload_json,phase
                    ) VALUES %s
                    """.formatted(rows(batch.size(), "(?,'STREAM_ORDER',?,?,?,?,?,?)")), recoveryRows.toArray());
            Map<String, Map<String, Object>> recoveries = new HashMap<>();
            for (Map<String, Object> row : jdbc.queryForList("""
                    SELECT source_id,order_id,voucher_id,user_id,idempotency_key,phase,
                           (claim_token IS NULL OR claimed_until<CURRENT_TIMESTAMP(3)) IS TRUE AS claimable
                    FROM commerce_order_recovery
                    WHERE source_id IN (%s)
                    ORDER BY source_id
                    FOR UPDATE
                    """.formatted(placeholders(batch.size(), 1)),
                    batch.stream().map(order -> (Object) order.sourceId()).toArray())) {
                recoveries.put(String.valueOf(row.get("source_id")), row);
            }
            Map<Long, CommerceAdmissionDurabilityService.Status> ledger =
                    durableAdmissions.lockBatchCandidates(voucherId, batch);
            List<BatchOrder> eligible = new ArrayList<>();
            List<BatchOrder> skipped = new ArrayList<>();
            for (BatchOrder order : batch) {
                Map<String, Object> row = recoveries.get(order.sourceId());
                boolean ready = row != null
                        && PHASE_RECEIVED.equals(row.get("phase"))
                        && ((Number) row.get("claimable")).intValue() == 1
                        && ((Number) row.get("order_id")).longValue() == order.orderId()
                        && ((Number) row.get("voucher_id")).longValue() == voucherId
                        && ((Number) row.get("user_id")).longValue() == order.userId()
                        && order.idempotencyKey().equals(row.get("idempotency_key"))
                        && ledger.containsKey(order.orderId());
                (ready ? eligible : skipped).add(order);
            }
            if (eligible.isEmpty()) return new BatchResult(List.of(), skipped, 0);
            int n = eligible.size();
            Object[] orderIds = eligible.stream().map(order -> (Object) order.orderId()).toArray();

            List<Object> reservations = new ArrayList<>();
            for (BatchOrder order : eligible) {
                reservations.addAll(List.of(order.orderId(), voucherId, order.userId(), order.sourceId()));
            }
            jdbc.update("""
                    INSERT INTO commerce_reservation(order_id,voucher_id,user_id,status,stream_record_id)
                    VALUES %s
                    """.formatted(rows(n, "(?,?,?,'RESERVED',?)")), reservations.toArray());
            // 与逐条路径同一防超卖边界，只是一次扣 N 份；不足时整批回滚，由逐条路径逐单判定。
            int stock = jdbc.update("""
                    UPDATE commerce_seckill_voucher
                    SET stock=stock-?, version=version+?
                    WHERE voucher_id=? AND stock>=?
                    """, n, n, voucherId, n);
            if (stock != 1) throw new BatchRejected("database stock is below batch size " + n);
            Map<String, Object> voucher = jdbc.queryForMap("""
                    SELECT v.id, v.shop_id, v.title, v.sub_title, v.rules,
                           v.pay_value, v.actual_value, p.shop_name
                    FROM commerce_voucher v
                    LEFT JOIN shop_summary_projection p ON p.shop_id=v.shop_id
                    WHERE v.id=?
                    """, voucherId);
            List<Object> orderRows = new ArrayList<>();
            List<Object> outboxRows = new ArrayList<>();
            for (BatchOrder order : eligible) {
                orderRows.addAll(Arrays.asList(order.orderId(), order.userId(), voucherId,
                        voucher.get("shop_id"), value(voucher.get("shop_name")), value(voucher.get("title")),
                        value(voucher.get("sub_title")), value(voucher.get("rules")),
                        voucher.get("pay_value"), voucher.get("actual_value"), order.idempotencyKey()));
                EventEnvelope<OrderCreatedEvent> event = orderCreatedEvent(order.orderId(), order.userId(), voucherId,
                        ((Number) voucher.get("shop_id")).longValue());
                outboxRows.addAll(List.of(event.eventId(), event.eventType(), event.aggregateType(),
                        event.aggregateId(), event.aggregateVersion(), json(event)));
            }
            jdbc.update("""
                    INSERT INTO commerce_order(
                        id,user_id,voucher_id,shop_id,shop_name_snapshot,
                        voucher_title_snapshot,voucher_sub_title_snapshot,voucher_rules_snapshot,
                        pay_value_snapshot,actual_value_snapshot,status,version,idempotency_key
                    ) VALUES %s
                    """.formatted(rows(n, "(?,?,?,?,?,?,?,?,?,?,1,0,?)")), orderRows.toArray());
            int reserved = jdbc.update("""
                    UPDATE commerce_reservation
                    SET status='ORDER_CREATED', version=version+1
                    WHERE order_id IN (%s) AND status='RESERVED'
                    """.formatted(placeholders(n, 1)), orderIds);
            if (reserved != n) throw new BatchRejected("reservations changed " + reserved + " of " + n);
            jdbc.update("""
                    INSERT INTO commerce_outbox_event(
                        event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                        topic,payload_json,status
                    ) VALUES %s
                    """.formatted(rows(n, "(?,?,?,?,?,'citycopilot-commerce-events-v1',?,'READY')")),
                    outboxRows.toArray());
            int accepted = durableAdmissions.markOrdersCreated(voucherId, eligible, ledger);
            int terminal = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, terminal_at=CURRENT_TIMESTAMP(3),
                        claim_token=NULL, claim_owner=NULL, claimed_until=NULL
                    WHERE source_id IN (%s) AND phase=?
                    """.formatted(placeholders(n, 1)), concat(PHASE_ORDER_CREATED,
                    eligible.stream().map(BatchOrder::sourceId).toArray(), PHASE_RECEIVED));
            if (terminal != n) throw new BatchRejected("recovery rows changed " + terminal + " of " + n);
            return new BatchResult(eligible, skipped, accepted);
        });
    }

    /** @return {@code count} 组括号占位，每组 {@code width} 个参数，例如 (?,?),(?,?)；width 为 1 时不加括号 */
    static String placeholders(int count, int width) {
        String group = width == 1 ? "?" : "(" + String.join(",", Collections.nCopies(width, "?")) + ")";
        return String.join(",", Collections.nCopies(count, group));
    }

    private static String rows(int count, String row) {
        return String.join(",", Collections.nCopies(count, row));
    }

    private static Object[] concat(Object first, Object[] middle, Object last) {
        Object[] all = new Object[middle.length + 2];
        all[0] = first;
        System.arraycopy(middle, 0, all, 1, middle.length);
        all[all.length - 1] = last;
        return all;
    }

    /**
     * 查询订单快照；非管理员查询自动附加 user_id 条件，避免越权枚举。
     *
     * @param orderId 订单 ID
     * @param userId 当前用户 ID
     * @param admin 是否使用平台管理员范围
     * @param merchant 是否使用商家范围；只能读取本人名下店铺的订单
     * @return 规范化订单字段
     */
    public OrderView order(long orderId, long userId, boolean admin, boolean merchant) {
        OrderView view = order(orderId, userId, admin || merchant, null);
        if (merchant && !admin) {
            place.requireWriteAuthority(view.shopId(), userId, false);
        }
        return view;
    }

    private OrderView order(long orderId, long userId, boolean admin, String inventoryRecovery) {
        String sql = """
                SELECT id,user_id,voucher_id,shop_id,shop_name_snapshot,
                       voucher_title_snapshot,voucher_sub_title_snapshot,voucher_rules_snapshot,
                       pay_value_snapshot,actual_value_snapshot,pay_type,status,close_reason,version,
                       create_time,pay_time,use_time,refund_time,update_time
                FROM commerce_order WHERE id=?
                """ + (admin ? "" : " AND user_id=?\n") + """
                UNION ALL
                SELECT l.order_id,l.user_id,l.voucher_id,v.shop_id,
                       NULL,NULL,NULL,NULL,NULL,NULL,NULL,
                       CASE l.status WHEN 'ACCEPTED' THEN 0 ELSE -1 END,NULL,0,
                       l.accepted_at,NULL,NULL,NULL,l.updated_at
                FROM commerce_admission_ledger l JOIN commerce_voucher v ON v.id=l.voucher_id
                WHERE l.order_id=? AND l.status IN ('ACCEPTED','REPAIR_REQUIRED')
                """ + (admin ? "" : " AND l.user_id=?");
        List<OrderView> rows = jdbc.query(sql, (row, index) -> {
            int status = row.getInt("status");
            if (status == -1) throw new CommerceApiException("COMMERCE_ADMISSION_REPAIR_REQUIRED",
                    "订单已受理，但生成失败，需要管理员修复后再查询", 503);
            OrderStatus presentation = orderStatus(status, row.getString("close_reason"));
            return new OrderView(
                    row.getString("id"), row.getLong("user_id"), row.getLong("voucher_id"), row.getLong("shop_id"),
                    row.getString("shop_name_snapshot"), row.getString("voucher_title_snapshot"),
                    row.getString("voucher_sub_title_snapshot"), row.getString("voucher_rules_snapshot"),
                    row.getObject("pay_value_snapshot", Integer.class), row.getObject("actual_value_snapshot", Integer.class), row.getObject("pay_type", Integer.class),
                    status, presentation.state(), presentation.statusText(), row.getInt("version"), row.getTimestamp("create_time"),
                    row.getTimestamp("pay_time"), row.getTimestamp("use_time"), row.getTimestamp("refund_time"),
                    row.getTimestamp("update_time"), inventoryRecovery);
        },
                admin ? new Object[]{orderId, orderId} : new Object[]{orderId, userId, orderId, userId});
        if (rows.isEmpty()) {
            throw new CommerceApiException("COMMERCE_ORDER_NOT_FOUND", "订单不存在或无权查看，请核对订单号", 404);
        }
        return rows.get(0);
    }

    /** 按订单 ID 倒序查询用户订单，每页 10 条且页码小于 1 时按第一页处理。 */
    public List<OrderSummary> myOrders(long userId, int page) {
        return jdbc.query("""
                SELECT id,user_id,voucher_id,shop_id,shop_name_snapshot,
                       voucher_title_snapshot,voucher_sub_title_snapshot,voucher_rules_snapshot,
                       pay_value_snapshot,actual_value_snapshot,status,close_reason,version,create_time,update_time
                FROM commerce_order
                WHERE user_id=?
                ORDER BY id DESC
                LIMIT 10 OFFSET ?
                """, (row, index) -> {
            int status = row.getInt("status");
            OrderStatus presentation = orderStatus(status, row.getString("close_reason"));
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
    private static OrderStatus orderStatus(int status, String closeReason) {
        return switch (status) {
            case 0 -> new OrderStatus("processing", "订单处理中");
            case 1 -> new OrderStatus("unpaid", "待支付");
            case 2 -> new OrderStatus("paid", "已支付");
            case 3 -> new OrderStatus("used", "已核销");
            case 4 -> new OrderStatus("cancelled", "PAY_TIMEOUT".equals(closeReason) ? "超时已取消" : "已取消");
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
     * @param action cancel、use 或 refund；支付只由验签回调推进
     * @param admin 是否以平台管理员范围操作
     * @param merchant 是否以商家范围操作；商家只能核销本人名下店铺的订单
     * @return 流转后的订单及可能的库存恢复状态
     */
    public OrderView transition(long orderId, long userId, String action,
                                boolean admin, boolean merchant) {
        if (merchant && !admin && !"use".equals(action)) {
            throw new CommerceApiException("COMMERCE_MERCHANT_ACTION_FORBIDDEN", "Merchants may only check in orders", 403);
        }
        boolean unscoped = admin || merchant;
        Map<String, Transition> transitions = Map.of(
                "cancel", new Transition(1, 4),
                "use", new Transition(2, 3),
                "refund", new Transition(2, 6)
        );
        Transition transition = transitions.get(action);
        if (transition == null) {
            throw new CommerceApiException("COMMERCE_ACTION_UNSUPPORTED", "Unsupported order action", 400);
        }
        String ownerClause = unscoped ? "" : " AND user_id=?";
        String timeColumn = switch (action) {
            case "use" -> "use_time";
            case "refund" -> "refund_time";
            default -> "update_time";
        };
        String sql = "UPDATE commerce_order SET status=?, version=version+1, "
                + timeColumn + "=CURRENT_TIMESTAMP(3)"
                + ("cancel".equals(action) ? ", close_reason='USER_CANCELLED'" : "")
                + " WHERE id=? AND status=?" + ownerClause;
        OrderView before = order(orderId, userId, admin, merchant);
        if ("processing".equals(before.state())) throw new CommerceApiException("COMMERCE_ORDER_PROCESSING",
                "订单仍在生成中，请等待订单确认后再操作", 409);
        long voucherId = before.voucherId();
        boolean returnsInventory = "cancel".equals(action) || "refund".equals(action);
        String fenceToken = returnsInventory ? acquireInventoryFence(voucherId) : null;
        String recoverySourceId = returnsInventory
                ? "transition:" + orderId + ":" + action + ":" + before.version()
                : null;
        boolean transitionCommitted = false;
        try {
            Boolean changed = transactions.execute(status -> {
                int updated = unscoped
                        ? jdbc.update(sql, transition.to(), orderId, transition.from())
                        : jdbc.update(sql, transition.to(), orderId, transition.from(), userId);
                if (updated != 1) return false;
                if ("refund".equals(action)) {
                    int refunded = jdbc.update("UPDATE commerce_payment SET status='REFUNDED' WHERE order_id=? AND status='PAID'", orderId);
                    if (refunded != 1) throw new CommerceApiException("COMMERCE_PAYMENT_STATE_CONFLICT", "支付单当前不能退款，请管理员核对支付记录", 409);
                }
                if (returnsInventory) {
                    recordInventoryReturn(recoverySourceId, orderId, voucherId, before.userId(),
                            action, transition.to(), fenceToken);
                }
                // 事件与恢复记录描述订单主人；管理员或商家只是操作者。
                appendStateEvent(orderId, before.userId(), action, transition.to());
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
                        before.userId(),
                        transition.to() == 4,
                        fenceToken
                );
            }
            return order(orderId, userId, unscoped,
                    PHASE_RESTORED.equals(inventoryRecovery) ? null : inventoryRecovery);
        } finally {
            if (fenceToken != null && !transitionCommitted) {
                releaseInventoryFence(voucherId, fenceToken);
            }
        }
    }

    /**
     * 按券成批关闭到期未付订单：每批一个事务、一次库存 fence、一次库存行更新，提交后一次 Lua 返还 Redis 库存。
     *
     * <p>先不加锁找出最早到期订单所在的券，再锁定这张券最多 {@value #EXPIRY_BATCH_SIZE} 张到期订单，被支付等事务
     * 锁住的订单由 SKIP LOCKED 跳过、留给后续轮次。库存维护中、或到期订单全被锁住的券在本轮余下查询中整体排除：
     * 否则它的到期订单每轮都排在最前面被反复处理，其他券的到期订单轮不到。每轮按时间预算处理。</p>
     */
    public int expireDueOrders() {
        int expired = 0;
        Set<Long> skipped = new LinkedHashSet<>();
        long budgetEnds = System.nanoTime() + EXPIRY_BUDGET_NANOS;
        while (System.nanoTime() < budgetEnds) {
            String excluded = skipped.isEmpty() ? ""
                    : "AND voucher_id NOT IN (" + placeholders(skipped.size(), 1) + ")";
            List<Long> head = jdbc.queryForList("""
                    SELECT voucher_id FROM commerce_order
                    WHERE status=1 AND pay_deadline<=CURRENT_TIMESTAMP(3) %s
                    ORDER BY pay_deadline,id LIMIT 1
                    """.formatted(excluded), Long.class, skipped.toArray());
            if (head.isEmpty()) break;
            long voucherId = head.get(0);
            ExpiryBatch batch = transactions.execute(transaction -> closeDueOrders(voucherId));
            if (batch == null || batch.fenceToken() == null) {
                skipped.add(voucherId);
                continue;
            }
            restoreTransitionInventory(voucherId, batch.fenceToken(), batch.restores());
            expired += batch.restores().size();
        }
        return expired;
    }

    /**
     * 在当前事务内关闭一张券的一批到期订单，并写入库存返还、Recovery 与 Outbox。
     *
     * @return 没有可锁定的到期订单时为空；券库存维护中时 fenceToken 为空
     */
    private ExpiryBatch closeDueOrders(long voucherId) {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id,user_id,version FROM commerce_order
                WHERE voucher_id=? AND status=1 AND pay_deadline<=CURRENT_TIMESTAMP(3)
                ORDER BY pay_deadline,id LIMIT ? FOR UPDATE SKIP LOCKED
                """, voucherId, EXPIRY_BATCH_SIZE);
        if (due.isEmpty()) return null;
        String fenceToken;
        try {
            fenceToken = acquireInventoryFence(voucherId);
        } catch (CommerceApiException busy) {
            if ("COMMERCE_INVENTORY_MAINTENANCE".equals(busy.code())) return new ExpiryBatch(null, List.of());
            throw busy;
        }
        try {
            List<TransitionRestore> restores = due.stream().map(row -> {
                long orderId = ((Number) row.get("id")).longValue();
                return new TransitionRestore("transition:" + orderId + ":cancel:" + row.get("version"),
                        orderId, ((Number) row.get("user_id")).longValue(), true);
            }).toList();
            Object[] ids = restores.stream().map(TransitionRestore::orderId).toArray();
            int changed = jdbc.update("""
                    UPDATE commerce_order SET status=4,close_reason='PAY_TIMEOUT',version=version+1
                    WHERE id IN (%s) AND status=1
                    """.formatted(placeholders(ids.length, 1)), ids);
            // 这些行已在本事务内加锁，数量不符说明锁定失效；整批回滚，不能按错误的件数返库存。
            if (changed != ids.length) {
                throw new IllegalStateException("Expired " + changed + " of " + ids.length + " locked orders");
            }
            recordInventoryReturns(voucherId, restores, fenceToken);
            appendCancelEvents(voucherId, restores);
            return new ExpiryBatch(fenceToken, restores);
        } catch (RuntimeException failure) {
            releaseInventoryFence(voucherId, fenceToken);
            throw failure;
        }
    }

    /** 一批同券订单的库存返还：券库存一次加回，支付与预留批量关闭，每单一条 RESTORE_PENDING Recovery。 */
    private void recordInventoryReturns(long voucherId, List<TransitionRestore> restores, String fenceToken) {
        int n = restores.size();
        Object[] ids = restores.stream().map(TransitionRestore::orderId).toArray();
        jdbc.update("UPDATE commerce_seckill_voucher SET stock=stock+?,version=version+1 WHERE voucher_id=?",
                n, voucherId);
        jdbc.update("UPDATE commerce_payment SET status='CLOSED' WHERE order_id IN (%s) AND status='PENDING'"
                .formatted(placeholders(n, 1)), ids);
        jdbc.update("UPDATE commerce_reservation SET status='CANCELLED',version=version+1 WHERE order_id IN (%s)"
                .formatted(placeholders(n, 1)), ids);
        String payload = json(Map.of("action", "cancel", "targetStatus", 4));
        List<Object> arguments = new ArrayList<>();
        for (TransitionRestore restore : restores) {
            arguments.addAll(List.of(restore.sourceId(), restore.orderId(), voucherId, restore.userId(),
                    payload, PHASE_RESTORE_PENDING, fenceToken));
        }
        jdbc.update("""
                INSERT INTO commerce_order_recovery(
                    source_id,source_type,order_id,voucher_id,user_id,payload_json,
                    phase,inventory_fence_token,compensation_delta
                ) VALUES %s
                """.formatted(rows(n, "(?,'STATE_TRANSITION',?,?,?,?,?,?,1)")), arguments.toArray());
    }

    /** 一批同券订单的取消事件；同券订单属于同一店铺，店铺只查一次。 */
    private void appendCancelEvents(long voucherId, List<TransitionRestore> restores) {
        long shopId = jdbc.queryForObject("SELECT shop_id FROM commerce_order WHERE id=?", Long.class,
                restores.get(0).orderId());
        List<Object> arguments = new ArrayList<>();
        for (TransitionRestore restore : restores) {
            EventEnvelope<Map<String, Object>> event = stateEvent(
                    restore.orderId(), restore.userId(), "cancel", 4, shopId, voucherId);
            arguments.addAll(List.of(event.eventId(), event.eventType(), event.aggregateType(),
                    event.aggregateId(), event.aggregateVersion(), json(event)));
        }
        jdbc.update("""
                INSERT INTO commerce_outbox_event(
                    event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                    topic,payload_json,status
                ) VALUES %s
                """.formatted(rows(restores.size(), "(?,?,?,?,?,'citycopilot-commerce-events-v1',?,'READY')")),
                arguments.toArray());
    }

    private record ExpiryBatch(String fenceToken, List<TransitionRestore> restores) {}

    /** 一单取消/退款后的 Redis 库存返还身份；同一批共享一个库存 fence。 */
    record TransitionRestore(String sourceId, long orderId, long userId, boolean releaseBuyer) {}

    private void recordInventoryReturn(String sourceId, long orderId, long voucherId, long userId,
                                       String action, int targetStatus, String fenceToken) {
        jdbc.update("""
                UPDATE commerce_seckill_voucher SET stock=stock+1,version=version+1 WHERE voucher_id=?
                """, voucherId);
        if (targetStatus == 4) {
            jdbc.update("UPDATE commerce_payment SET status='CLOSED' WHERE order_id=? AND status='PENDING'", orderId);
            jdbc.update("""
                    UPDATE commerce_reservation SET status='CANCELLED',version=version+1 WHERE order_id=?
                    """, orderId);
        }
        jdbc.update("""
                INSERT INTO commerce_order_recovery(
                    source_id,source_type,order_id,voucher_id,user_id,payload_json,
                    phase,inventory_fence_token,compensation_delta
                ) VALUES (?,'STATE_TRANSITION',?,?,?,?,?,?,1)
                """, sourceId, orderId, voucherId, userId,
                json(Map.of("action", action, "targetStatus", targetStatus)), PHASE_RESTORE_PENDING, fenceToken);
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
            long userId,
            boolean releaseBuyer,
            String fenceToken
    ) {
        Long restored;
        try {
            restored = redis.execute(
                    RESTORE_INVENTORY_SCRIPT,
                    List.of(
                            keyspace.fence(voucherId),
                            keyspace.stock(voucherId),
                            keyspace.restoreMarker(voucherId, sourceId),
                            keyspace.buyers(voucherId)
                    ),
                    fenceToken, String.valueOf(userId), String.valueOf(releaseBuyer)
            );
        } catch (Exception unavailable) {
            // 保持 RESTORE_PENDING；Lua marker 能区分“已增加库存”和“尚未执行”，调度器可安全重试。
            log.warn("Inventory restore remains pending for order {}", orderId);
            return PHASE_RESTORE_PENDING;
        }
        if (restored != null && (restored == 1L || restored == 2L)) {
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
            return PHASE_RESTORED;
        }
        if (restored != null && restored == -2L && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT redis_archived_at IS NOT NULL FROM commerce_seckill_voucher WHERE voucher_id=?",
                Boolean.class, voucherId))) {
            // 活动已归档、Redis 状态已释放；MySQL 库存已在状态事务内返还，无需再恢复 Redis。
            jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, compensation_delta=0, terminal_at=CURRENT_TIMESTAMP(3)
                    WHERE source_id=? AND phase=?
                    """, PHASE_RESTORED, sourceId, PHASE_RESTORE_PENDING);
            return PHASE_RESTORED;
        }
        String reason = restored != null && restored == -2L
                 ? "redis_stock_missing_during_transition_restore"
                : "inventory_fence_expired_before_transition_restore";
        jdbc.update("""
                UPDATE commerce_order_recovery
                SET phase=?, compensation_delta=1, last_error=?,
                    terminal_at=CURRENT_TIMESTAMP(3)
                WHERE source_id=? AND phase=?
                """, PHASE_REPAIR_REQUIRED, reason, sourceId, PHASE_RESTORE_PENDING);
        releaseInventoryFence(voucherId, fenceToken);
        return PHASE_REPAIR_REQUIRED;
    }

    /**
     * 用同一个 fence 令牌一次返还一批取消订单的 Redis 库存，再把 Recovery 行批量标为 RESTORED。
     *
     * <p>逐单结果与 {@link #attemptTransitionInventoryRestore} 一致：Redis 不可用时保持 RESTORE_PENDING，由轮询用原令牌
     * 重试；fence 已不属于本批或库存键缺失时，已有 marker 的单（此前已返还）记为 RESTORED，其余转人工修复。
     * 同一批的单必须共享令牌整体重试：逐单脚本成功一单就会释放 fence，其余单会被误判为 fence 已过期。</p>
     */
    void restoreTransitionInventory(long voucherId, String fenceToken, List<TransitionRestore> restores) {
        List<String> keys = new ArrayList<>(List.of(
                keyspace.fence(voucherId), keyspace.stock(voucherId), keyspace.buyers(voucherId)));
        List<String> markers = new ArrayList<>();
        List<String> arguments = new ArrayList<>(List.of(fenceToken));
        for (TransitionRestore restore : restores) {
            markers.add(keyspace.restoreMarker(voucherId, restore.sourceId()));
            arguments.add(String.valueOf(restore.userId()));
            arguments.add(String.valueOf(restore.releaseBuyer()));
        }
        keys.addAll(markers);
        Long result;
        try {
            result = redis.execute(RESTORE_INVENTORY_BATCH_SCRIPT, keys, arguments.toArray());
        } catch (Exception unavailable) {
            log.warn("Inventory restore remains pending for {} orders of voucher {}", restores.size(), voucherId);
            return;
        }
        if (result != null && result >= 0) {
            markRestored(restores);
            try {
                redis.unlink(markers);
            } catch (Exception cleanupFailure) {
                log.debug("Unable to remove completed inventory restore markers of voucher {}", voucherId);
            }
            return;
        }
        boolean stockMissing = result != null && result == -2L;
        // 活动已归档、Redis 状态已释放；MySQL 库存已在状态事务内返还，无需再恢复 Redis。
        boolean archived = stockMissing && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT redis_archived_at IS NOT NULL FROM commerce_seckill_voucher WHERE voucher_id=?",
                Boolean.class, voucherId));
        List<TransitionRestore> restored = new ArrayList<>();
        List<TransitionRestore> unresolved = new ArrayList<>();
        for (int i = 0; i < restores.size(); i++) {
            boolean done = archived || Boolean.TRUE.equals(redis.hasKey(markers.get(i)));
            (done ? restored : unresolved).add(restores.get(i));
        }
        markRestored(restored);
        if (!unresolved.isEmpty()) {
            List<Object> repair = new ArrayList<>(List.of(PHASE_REPAIR_REQUIRED, stockMissing
                    ? "redis_stock_missing_during_transition_restore"
                    : "inventory_fence_expired_before_transition_restore"));
            unresolved.forEach(restore -> repair.add(restore.sourceId()));
            repair.add(PHASE_RESTORE_PENDING);
            jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, compensation_delta=1, last_error=?, terminal_at=CURRENT_TIMESTAMP(3)
                    WHERE source_id IN (%s) AND phase=?
                    """.formatted(placeholders(unresolved.size(), 1)), repair.toArray());
        }
        releaseInventoryFence(voucherId, fenceToken);
    }

    private void markRestored(List<TransitionRestore> restores) {
        if (restores.isEmpty()) return;
        jdbc.update("""
                UPDATE commerce_order_recovery
                SET phase=?, compensation_delta=0, terminal_at=CURRENT_TIMESTAMP(3)
                WHERE source_id IN (%s) AND phase=?
                """.formatted(placeholders(restores.size(), 1)), concat(PHASE_RESTORED,
                restores.stream().map(TransitionRestore::sourceId).toArray(), PHASE_RESTORE_PENDING));
    }

    /** 在订单状态事务内追加 Outbox 事件，交由 Relay 异步发布。 */
    void appendStateEvent(long orderId, long userId, String action, int state) {
        // 同一事务内按主键读出店铺和券，消费端（如店铺销量）无需回查 Commerce。schemaVersion 2 只新增这两个字段。
        Map<String, Object> order = jdbc.queryForMap("SELECT shop_id,voucher_id FROM commerce_order WHERE id=?", orderId);
        EventEnvelope<Map<String, Object>> event = stateEvent(orderId, userId, action, state,
                ((Number) order.get("shop_id")).longValue(), ((Number) order.get("voucher_id")).longValue());
        jdbc.update("""
                INSERT INTO commerce_outbox_event(
                    event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                    topic,payload_json,status
                ) VALUES (?,?,?,?,?,'citycopilot-commerce-events-v1',?,'READY')
                """,
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateVersion(),
                json(event)
        );
    }

    private EventEnvelope<Map<String, Object>> stateEvent(long orderId, long userId, String action, int state,
                                                          long shopId, long voucherId) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(), "commerce.order." + action, 2, "commerce_order",
                String.valueOf(orderId), state, "commerce-service", Instant.now().toString(), String.valueOf(orderId),
                Map.of("orderId", orderId, "userId", userId, "action", action, "status", state,
                        "shopId", shopId, "voucherId", voucherId));
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
