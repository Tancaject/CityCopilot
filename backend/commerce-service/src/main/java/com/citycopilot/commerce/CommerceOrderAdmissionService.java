package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.AdmissionAccepted;
import com.citycopilot.contract.commerce.CommerceAdmissionOutcomePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** 固定准入身份、执行 Redis 原子准入并恢复已经持久接受的请求。 */
@Service
public class CommerceOrderAdmissionService {
    private static final Logger log = LoggerFactory.getLogger(CommerceOrderAdmissionService.class);
    private final JdbcTemplate jdbc;
    private final CommerceOrderIdGenerator orderIds;
    private final CommerceAdmissionDurabilityService durableAdmissions;
    private final CommerceRedisReplicationExecutor replicatedRedis;
    private final CommerceRedisKeyspace keyspace;
    private final CommerceRuntimeMutationLeases runtimeLeases;
    private final CommerceRoutingGuard routing;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_seckill.lua");
    private static final DefaultRedisScript<Long> RECOVER_ADMISSION_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_recover_admission.lua");

    public CommerceOrderAdmissionService(JdbcTemplate jdbc, CommerceOrderIdGenerator orderIds,
            CommerceAdmissionDurabilityService durableAdmissions, CommerceRedisReplicationExecutor replicatedRedis,
            CommerceRedisKeyspace keyspace, CommerceRuntimeMutationLeases runtimeLeases, CommerceRoutingGuard routing) {
        this.jdbc = jdbc;
        this.orderIds = orderIds;
        this.durableAdmissions = durableAdmissions;
        this.replicatedRedis = replicatedRedis;
        this.keyspace = keyspace;
        this.runtimeLeases = runtimeLeases;
        this.routing = routing;
    }

    /**
     * 执行秒杀准入；相同用户和幂等键必须得到同一订单身份或同一稳定拒绝。
     *
     * @param voucherId Voucher ID
     * @param userId 用户 ID
     * @param idempotencyKey 客户端重试时复用的幂等键
     * @return processing 状态及订单 ID；首次受理 code 为 COMMERCE_ORDER_ACCEPTED
     */
    public AdmissionAccepted seckill(long voucherId, long userId, String idempotencyKey) {
        requirePositive(voucherId, "voucherId");
        requirePositive(userId, "userId");
        String key = normalizeRequiredIdempotencyKey(idempotencyKey);
        if (runtimeLeases == null) {
            return seckillWithAdmissionLease(voucherId, userId, key, null);
        }
        try (CommerceRuntimeMutationLeases.MutationLease lease =
                     runtimeLeases.acquireAdmission(voucherId)) {
            routing.requireAdmissionActive(voucherId);
            return seckillWithAdmissionLease(voucherId, userId, key, lease);
        }
    }

    /**
     * 在已取得分片准入租约后完成“持久化身份 -> 全局身份 -> Voucher Lua -> ACCEPTED”链路。
     */
    private AdmissionAccepted seckillWithAdmissionLease(
            long voucherId,
            long userId,
            String key,
            CommerceRuntimeMutationLeases.MutationLease lease
    ) {
        String requestFingerprint = requestFingerprint(voucherId);
        CommerceAdmissionDurabilityService.Admission durable = durableAdmissions.prepare(
                voucherId, userId, key, requestFingerprint, null
        );
        if (durable.wasAccepted()) {
            assertLease(lease);
            return accepted(durable.orderId(), key, "IDEMPOTENT_REPLAY");
        }
        try {
            AdmissionIdentity globalIdentity = reserveGlobalIdempotency(
                    voucherId, userId, key, requestFingerprint, durable.orderId()
            );
            // 重试也必须执行 Voucher 本地 Lua；同值幂等写让 node-bound WAIT 确认本 slot，
            // 而不是信任另一条连接上的普通读取。
            AdmissionAccepted response = admit(
                    voucherId,
                    userId,
                    globalIdentity.orderId(),
                    key,
                    requestFingerprint
            );
            durableAdmissions.markAccepted(durable);
            assertLease(lease);
            return response;
        } catch (CommerceApiException rejection) {
            persistStableRejection(durable, rejection);
            assertLease(lease);
            throw rejection;
        }
    }

    /**
     * 运维死信重放入口，强制复用原订单身份并走与普通请求相同的准入安全边界。
     */
    Map<String, Object> replayAdmission(
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey
    ) {
        requirePositive(orderId, "orderId");
        requirePositive(voucherId, "voucherId");
        requirePositive(userId, "userId");
        String key = normalizeIdempotencyKey(idempotencyKey, "dead-letter-order:" + orderId);
        if (runtimeLeases == null) {
            return replayAdmissionWithLease(orderId, voucherId, userId, key, null);
        }
        try (CommerceRuntimeMutationLeases.MutationLease lease =
                     runtimeLeases.acquireAdmission(voucherId)) {
            routing.requireAdmissionActive(voucherId);
            return replayAdmissionWithLease(orderId, voucherId, userId, key, lease);
        }
    }

    private Map<String, Object> replayAdmissionWithLease(
            long orderId,
            long voucherId,
            long userId,
            String key,
            CommerceRuntimeMutationLeases.MutationLease lease
    ) {
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id,status,version,user_id,voucher_id,idempotency_key
                FROM commerce_order
                WHERE id=? OR (user_id=? AND idempotency_key=?)
                LIMIT 1
                """, orderId, userId, key);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            boolean identical = ((Number) row.get("id")).longValue() == orderId
                    && ((Number) row.get("user_id")).longValue() == userId
                    && ((Number) row.get("voucher_id")).longValue() == voucherId
                    && key.equals(String.valueOf(row.get("idempotency_key")));
            if (!identical) {
                throw new CommerceApiException(
                        "COMMERCE_REPLAY_IDENTITY_CONFLICT",
                        "Dead-letter order identity conflicts with an existing order",
                        409
                );
            }
            assertLease(lease);
            return orderResult(row, "IDEMPOTENT_REPLAY");
        }
        String fingerprint = requestFingerprint(voucherId);
        CommerceAdmissionDurabilityService.Admission durable = durableAdmissions.prepare(
                voucherId, userId, key, fingerprint, orderId
        );
        if (durable.wasAccepted()) {
            assertLease(lease);
            return replayResponse(accepted(durable.orderId(), key, "IDEMPOTENT_REPLAY"));
        }
        try {
            AdmissionIdentity globalIdentity = reserveGlobalIdempotency(
                    voucherId, userId, key, fingerprint, orderId
            );
            if (globalIdentity.orderId() != orderId) {
                throw new CommerceApiException(
                        "COMMERCE_REPLAY_IDENTITY_CONFLICT",
                        "Dead-letter order identity conflicts with the global idempotency registry",
                        409
                );
            }
            AdmissionAccepted response = admit(voucherId, userId, orderId, key, fingerprint);
            durableAdmissions.markAccepted(durable);
            assertLease(lease);
            return replayResponse(response);
        } catch (CommerceApiException rejection) {
            persistStableRejection(durable, rejection);
            assertLease(lease);
            throw rejection;
        }
    }

    /**
     * 执行 Voucher 同槽原子准入并把 Lua 状态码映射为稳定业务结果。
     *
     * <p>复制确认失败时结果可能已经到达 Redis 主节点，因此返回“结果未知”，要求客户端以同一
     * 幂等键重试，而不能直接宣告失败并换键。</p>
     */
    private AdmissionAccepted admit(
            long voucherId,
            long userId,
            long orderId,
            String idempotencyKey,
            String requestFingerprint
    ) {
        String admissionKey = admissionKey(voucherId, userId, idempotencyKey);
        Long result;
        try {
            result = replicatedRedis.executeReplicatedScript(
                    "voucher_admission",
                    keyspace.stock(voucherId),
                    SECKILL_SCRIPT,
                    admissionScriptKeys(voucherId, userId, idempotencyKey),
                    String.valueOf(userId),
                    String.valueOf(orderId),
                    String.valueOf(voucherId),
                    idempotencyKey,
                    requestFingerprint,
                    keyspace.activeAdmissionGateValue()
            );
        } catch (RuntimeException error) {
            throw admissionOutcomeUnknown(error);
        }
        if (result == null || result == 5L || result == 7L) {
            throw admissionUnavailable();
        }
        if (result == 1L) {
            throw new CommerceApiException("SECKILL_STOCK_NOT_ENOUGH", "Voucher stock exhausted", 409);
        }
        if (result == 2L) {
            throw new CommerceApiException("SECKILL_DUPLICATE_ORDER", "User already purchased this voucher", 409);
        }
        if (result == 3L) {
            return accepted(orderId, idempotencyKey, "IDEMPOTENT_REPLAY");
        }
        if (result == 4L) {
            throw idempotencyConflict();
        }
        if (result == 6L) {
            throw new CommerceApiException(
                    "COMMERCE_INVENTORY_MAINTENANCE",
                    "Voucher inventory is under maintenance",
                    503
            );
        }
        if (result == 8L) {
            throw new CommerceApiException("VOUCHER_NOT_AVAILABLE", "Voucher is not active", 409);
        }
        if (result == 9L) {
            throw new CommerceApiException("SECKILL_NOT_STARTED", "Seckill has not started", 409);
        }
        if (result == 10L) {
            throw new CommerceApiException("SECKILL_ENDED", "Seckill has ended", 409);
        }
        if (result == 11L) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_NOT_ACTIVE",
                    "Commerce order admission is not active for this Redis shard",
                    503
            );
        }
        if (result != 0L) {
            throw admissionUnavailable();
        }
        return accepted(orderId, idempotencyKey, "COMMERCE_ORDER_ACCEPTED");
    }

    /**
     * 按 Lua KEYS 约定组装全部准入键；这些键必须共享 Voucher 的 Cluster hash slot。
     */
    private List<String> admissionScriptKeys(
            long voucherId,
            long userId,
            String idempotencyKey
    ) {
        return List.of(
                keyspace.campaignMeta(voucherId),
                keyspace.stock(voucherId),
                keyspace.buyers(voucherId),
                keyspace.stream(voucherId),
                admissionKey(voucherId, userId, idempotencyKey),
                keyspace.fence(voucherId),
                keyspace.inflight(voucherId),
                keyspace.admissionRoutingGate(voucherId)
        );
    }

    /** 只持久化以后可确定性重放的业务拒绝；临时故障和结果未知不得固化为 REJECTED。 */
    private void persistStableRejection(
            CommerceAdmissionDurabilityService.Admission durable,
            CommerceApiException rejection
    ) {
        if (durable.status() != CommerceAdmissionDurabilityService.Status.PREPARED) return;
        if (switch (rejection.code()) {
            case "SECKILL_STOCK_NOT_ENOUGH", "SECKILL_DUPLICATE_ORDER",
                    "COMMERCE_IDEMPOTENCY_CONFLICT", "COMMERCE_REPLAY_IDENTITY_CONFLICT",
                    "VOUCHER_NOT_AVAILABLE", "SECKILL_NOT_STARTED", "SECKILL_ENDED" -> true;
            default -> false;
        }) {
            // 若拒绝落库失败，markRejected 返回 503；不能先返回一个未来重试无法复现的拒绝。
            durableAdmissions.markRejected(durable, rejection);
        }
    }

    /**
     * 在跨 Voucher 全局注册表中永久绑定幂等键与订单身份，并确认其 Redis 副本写入。
     */
    private AdmissionIdentity reserveGlobalIdempotency(
            long voucherId,
            long userId,
            String idempotencyKey,
            String requestFingerprint,
            Long requiredOrderId
    ) {
        String registryKey = keyspace.globalIdempotency(userId, idempotencyKey);
        long candidateOrderId;
        try {
            candidateOrderId = requiredOrderId == null ? orderIds.nextId() : requiredOrderId;
            String stored = replicatedRedis.reserveGlobalIdentity(
                    registryKey,
                    admissionIdentity(candidateOrderId, voucherId, requestFingerprint)
            );
            AdmissionIdentity existing = admissionIdentity(stored);
            if (existing == null) throw admissionUnavailable();
            if (!existing.matches(voucherId, requestFingerprint)) {
                throw idempotencyConflict();
            }
            if (requiredOrderId != null && existing.orderId() != requiredOrderId) {
                throw new CommerceApiException(
                        "COMMERCE_REPLAY_IDENTITY_CONFLICT",
                        "Dead-letter order identity conflicts with the global idempotency registry",
                        409
                );
            }
            return existing;
        } catch (CommerceApiException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw admissionUnavailable();
        }
    }

    /**
     * 扫描持久化 ACCEPTED 账本，补回可能因请求崩溃而缺失的 Redis Stream 准入记录。
     */
    public void recoverDurableAdmissions() {
        for (CommerceAdmissionDurabilityService.Admission admission
                : durableAdmissions.claimAcceptedBatch()) {
            recoverDurableAdmission(admission);
        }
    }

    /**
     * 恢复单条持久准入：已有订单则终态化，否则按原身份幂等补齐 Redis 队列。
     */
    private void recoverDurableAdmission(
            CommerceAdmissionDurabilityService.Admission admission
    ) {
        try {
            if (durableOrderExists(admission)) {
                durableAdmissions.markOrderCreated(admission);
                return;
            }
            AdmissionIdentity global = reserveGlobalIdempotency(
                    admission.voucherId(),
                    admission.userId(),
                    admission.idempotencyKey(),
                    admission.requestFingerprint(),
                    admission.orderId()
            );
            if (global.orderId() != admission.orderId()) {
                throw new IllegalStateException("Durable admission global identity conflicts");
            }
            // 恢复 Lua 接受 ACTIVE 或 DRAINING 门禁，但仍要求代际匹配，便于排空旧在途任务。
            Long result = replicatedRedis.executeReplicatedScript(
                    "voucher_admission_recovery",
                    keyspace.stock(admission.voucherId()),
                    RECOVER_ADMISSION_SCRIPT,
                    admissionScriptKeys(
                            admission.voucherId(),
                            admission.userId(),
                            admission.idempotencyKey()
                    ),
                    String.valueOf(admission.userId()),
                    String.valueOf(admission.orderId()),
                    String.valueOf(admission.voucherId()),
                    admission.idempotencyKey(),
                    admission.requestFingerprint(),
                    keyspace.activeAdmissionGateValue(),
                    keyspace.drainingAdmissionGateValue()
            );
            if (result == null || result != 0L && result != 3L) {
                throw new IllegalStateException(
                        "Redis durable admission recovery returned " + result
                );
            }
            durableAdmissions.releaseAfterQueueConfirmation(admission);
        } catch (RuntimeException failure) {
            try {
                durableAdmissions.releaseForRetryOrRepair(admission, failure);
            } catch (RuntimeException ledgerFailure) {
                log.error(
                        "Unable to record failed durable admission recovery for order {}",
                        admission.orderId(),
                        ledgerFailure
                );
            }
        }
    }

    /**
     * 检查准入对应订单是否已存在，并拒绝任何 ID/用户/Voucher/幂等键冲突。
     */
    private boolean durableOrderExists(
            CommerceAdmissionDurabilityService.Admission admission
    ) {
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT id,user_id,voucher_id,idempotency_key
                FROM commerce_order
                WHERE id=? OR (user_id=? AND idempotency_key=?)
                LIMIT 2
                """, admission.orderId(), admission.userId(), admission.idempotencyKey());
        if (orders.isEmpty()) return false;
        if (orders.size() != 1) {
            throw new IllegalStateException("Durable admission matches multiple Commerce orders");
        }
        Map<String, Object> order = orders.get(0);
        boolean identical = ((Number) order.get("id")).longValue() == admission.orderId()
                && ((Number) order.get("user_id")).longValue() == admission.userId()
                && ((Number) order.get("voucher_id")).longValue() == admission.voucherId()
                && admission.idempotencyKey().equals(String.valueOf(order.get("idempotency_key")));
        if (!identical) {
            throw new IllegalStateException("Durable admission conflicts with an existing Commerce order");
        }
        return true;
    }

    private void assertLease(CommerceRuntimeMutationLeases.MutationLease lease) {
        if (lease != null) lease.renewAndAssert();
    }

    private Map<String, Object> orderResult(Map<String, Object> row, String code) {
        return Map.of(
                "orderId", String.valueOf(row.get("id")),
                "status", row.get("status"),
                "version", row.get("version"),
                "state", "completed",
                "code", code
        );
    }

    /** 生成异步准入响应；此时订单可能尚未写入 MySQL，客户端应按 orderId 轮询。 */
    private AdmissionAccepted accepted(long orderId, String idempotencyKey, String code) {
        return new AdmissionAccepted(String.valueOf(orderId), "processing", code, idempotencyKey);
    }

    /** 死信重放还支持 completed 分支，暂保留它已有的独立响应形状。 */
    private Map<String, Object> replayResponse(AdmissionAccepted accepted) {
        return Map.of(
                "orderId", accepted.orderId(),
                "state", accepted.state(),
                "code", accepted.code(),
                "idempotencyKey", accepted.idempotencyKey()
        );
    }

    private String admissionKey(long voucherId, long userId, String idempotencyKey) {
        return keyspace.idempotency(voucherId, userId, idempotencyKey);
    }

    /** 解码全局幂等注册表值；无法证明 Voucher 身份的旧数字格式按无效处理。 */
    private AdmissionIdentity admissionIdentity(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String[] parts = encoded.split("\\|", 4);
        if (parts.length < 2) {
            // 旧数字格式无法证明 Voucher 身份，不能把它当作任意 Voucher 的合法重放。
            return null;
        }
        try {
            return new AdmissionIdentity(
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    parts.length >= 3 ? parts[2] : null,
                    parts.length == 4 ? parts[3] : null
            );
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private String admissionIdentity(
            long orderId,
            long voucherId,
            String requestFingerprint
    ) {
        return orderId + "|" + voucherId + "|" + requestFingerprint;
    }

    private String requestFingerprint(long voucherId) {
        return "seckill:v1:" + voucherId;
    }

    private CommerceApiException admissionUnavailable() {
        return new CommerceApiException(
                "COMMERCE_REDIS_UNAVAILABLE",
                "Seckill admission unavailable",
                503
        );
    }

    /**
     * Redis 写可能已成功但复制确认/连接响应失败时返回可重试“结果未知”，并保留根因。
     */
    private CommerceApiException admissionOutcomeUnknown(RuntimeException cause) {
        CommerceApiException error = new CommerceApiException(
                CommerceAdmissionOutcomePolicy.COMMERCE_ADMISSION_OUTCOME_UNKNOWN,
                "Admission outcome is unknown; retry with the same Idempotency-Key",
                503
        );
        error.initCause(cause);
        return error;
    }

    private CommerceApiException idempotencyConflict() {
        return new CommerceApiException(
                "COMMERCE_IDEMPOTENCY_CONFLICT",
                "Idempotency key is already in use for another voucher",
                409
        );
    }

    private String normalizeIdempotencyKey(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized.length() > 128) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "Idempotency-Key must not exceed 128 characters",
                    400
            );
        }
        return normalized;
    }

    private String normalizeRequiredIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new CommerceApiException(
                    "COMMERCE_IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required",
                    400
            );
        }
        return normalizeIdempotencyKey(value, "");
    }

    /**
     * 校验数据库形式的秒杀 Voucher 是否在给定时刻可准入。
     *
     * <p>主要供测试/兼容路径复用；生产热路径的同类校验由 Redis 活动元数据 Lua 原子完成。</p>
     */
    static void validateAdmissionConfig(Map<String, Object> voucher, Instant now) {
        if (((Number) voucher.get("type")).intValue() != 1) {
            throw new CommerceApiException("VOUCHER_NOT_SECKILL", "Voucher is not a seckill voucher", 409);
        }
        if (((Number) voucher.get("status")).intValue() != 1) {
            throw new CommerceApiException("VOUCHER_NOT_AVAILABLE", "Voucher is not active", 409);
        }
        Object stockValue = voucher.get("stock");
        if (!(stockValue instanceof Number stock) || stock.intValue() <= 0) {
            throw new CommerceApiException("SECKILL_STOCK_NOT_ENOUGH", "Voucher stock exhausted", 409);
        }
        Instant begin = instant(voucher.get("begin_time"));
        Instant end = instant(voucher.get("end_time"));
        if (begin == null || end == null) {
            throw new CommerceApiException(
                    "VOUCHER_NOT_AVAILABLE",
                    "Seckill voucher schedule is incomplete",
                    409
            );
        }
        if (now.isBefore(begin)) {
            throw new CommerceApiException("SECKILL_NOT_STARTED", "Seckill has not started", 409);
        }
        if (now.isAfter(end)) {
            throw new CommerceApiException("SECKILL_ENDED", "Seckill has ended", 409);
        }
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value instanceof LocalDateTime local) {
            return local.atZone(ZoneId.systemDefault()).toInstant();
        }
        return value == null ? null : Instant.parse(String.valueOf(value));
    }

    private void requirePositive(long value, String name) {
        if (value <= 0) throw new CommerceApiException("COMMERCE_INVALID_ARGUMENT", name + " must be positive", 400);
    }

    private record AdmissionIdentity(
            long orderId,
            long voucherId,
            String requestFingerprint,
            String sourceId
    ) {
        private AdmissionIdentity(long orderId, long voucherId, String requestFingerprint) {
            this(orderId, voucherId, requestFingerprint, null);
        }

        private boolean matches(long expectedVoucherId, String expectedFingerprint) {
            return voucherId == expectedVoucherId
                    && (requestFingerprint == null || requestFingerprint.equals(expectedFingerprint));
        }
    }


}
