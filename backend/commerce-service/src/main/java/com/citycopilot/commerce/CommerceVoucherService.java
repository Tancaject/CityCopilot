package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.VoucherCreated;
import com.citycopilot.contract.commerce.CommerceContracts.VoucherView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Voucher 的查询与写入服务，同时协调 Place 权限、MySQL 事务和 Redis 秒杀副本。
 *
 * <p>MySQL 是 Voucher 事实来源；秒杀库存和活动元数据只在数据库事务提交后初始化或刷新。
 * 直接库存调整被禁止，避免绕过 fence 和一致性修复流程。</p>
 */
@Service
public class CommerceVoucherService {
    /** 查询和修改 Voucher 及店铺投影。 */
    private final JdbcTemplate jdbc;
    /** 向 Place 权威服务确认店铺归属。 */
    private final PlaceAuthorityClient place;
    /** 为未指定 ID 的 Voucher 分配持久化 ID。 */
    private final CommerceOrderIdGenerator ids;
    /** 新建秒杀 Voucher 后初始化全新 Redis 库存。 */
    private final CommerceRedisInventoryBootstrap inventory;
    /** 管理同步准入所需的 Redis 活动元数据。 */
    private final CommerceRedisCampaignMetadata campaignMetadata;
    /** 让业务写与路由排空互斥；测试构造可为空。 */
    private final CommerceRuntimeMutationLeases runtimeLeases;
    /** 确认写操作仍属于当前 ACTIVE 路由代际。 */
    private final CommerceRoutingGuard routing;

    @Autowired
    public CommerceVoucherService(
            JdbcTemplate jdbc,
            PlaceAuthorityClient place,
            CommerceOrderIdGenerator ids,
            CommerceRedisInventoryBootstrap inventory,
            CommerceRedisCampaignMetadata campaignMetadata,
            CommerceRuntimeMutationLeases runtimeLeases,
            CommerceRoutingGuard routing
    ) {
        this.jdbc = jdbc;
        this.place = place;
        this.ids = ids;
        this.inventory = inventory;
        this.campaignMetadata = campaignMetadata;
        this.runtimeLeases = runtimeLeases;
        this.routing = routing;
    }

    CommerceVoucherService(
            JdbcTemplate jdbc,
            PlaceAuthorityClient place,
            CommerceOrderIdGenerator ids,
            CommerceRedisInventoryBootstrap inventory,
            CommerceRedisCampaignMetadata campaignMetadata
    ) {
        this(jdbc, place, ids, inventory, campaignMetadata, null, null);
    }

    /**
     * 查询指定店铺当前启用的 Voucher。
     *
     * @param shopId 店铺 ID
     * @return 含秒杀库存和时间窗的 Voucher 列表
     */
    public List<VoucherView> list(long shopId) {
        return jdbc.query("""
                SELECT v.*,s.stock,s.begin_time,s.end_time
                FROM commerce_voucher v
                LEFT JOIN commerce_seckill_voucher s ON s.voucher_id=v.id
                WHERE v.shop_id=? AND v.status=1
                ORDER BY v.id
                """, this::voucherRow, shopId);
    }

    /**
     * 管理端按店铺、标题关键字和状态筛选 Voucher，最多返回 100 条。
     */
    public List<VoucherView> adminList(Long shopId, String keyword, Integer status) {
        return jdbc.query("""
                SELECT v.*,s.stock,s.begin_time,s.end_time
                FROM commerce_voucher v
                LEFT JOIN commerce_seckill_voucher s ON s.voucher_id=v.id
                WHERE (? IS NULL OR v.shop_id=?)
                  AND (? IS NULL OR v.status=?)
                  AND (? IS NULL OR v.title LIKE CONCAT('%',?,'%'))
                ORDER BY v.id DESC
                LIMIT 100
                """, this::voucherRow, shopId, shopId, status, status, keyword, keyword);
    }

    /**
     * 在事务中创建 Voucher 和店铺快照投影；秒杀 Redis 状态在提交后建立。
     *
     * @param request Voucher 字段；秒杀类型还需 stock、beginTime、endTime
     * @param actorId 操作者用户 ID
     * @param admin 是否绕过店主匹配但仍校验店铺存在
     * @return 新 Voucher ID
     */
    @Transactional
    public VoucherCreated create(Map<String, Object> request, long actorId, boolean admin) {
        beginTransactionalMutation();
        long shopId = requiredLong(request, "shopId");
        Map<String, Object> authority = place.requireWriteAuthority(shopId, actorId, admin);
        validateCreate(request);
        Long requestedId = optionalLong(request.get("id"));
        if (requestedId != null && requestedId <= 0) {
            invalid("id must be positive");
        }
        long id = requestedId == null
                ? ids.nextVoucherId()
                : requestedId;
        int type = number(request.get("type"), 0);
        jdbc.update("""
                INSERT INTO commerce_voucher(
                    id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status
                ) VALUES (?,?,?,?,?,?,?,?,?)
                """,
                id, shopId, text(request, "title"), request.get("subTitle"), request.get("rules"),
                number(request.get("payValue"), 0), number(request.get("actualValue"), 0),
                type, number(request.get("status"), 1)
        );
        jdbc.update("""
                INSERT INTO shop_summary_projection(shop_id,shop_name,owner_user_id,source_version)
                VALUES (?,?,?,1)
                ON DUPLICATE KEY UPDATE shop_name=VALUES(shop_name),
                    owner_user_id=VALUES(owner_user_id),source_version=source_version+1
                """, shopId, authority.get("name"), authority.get("ownerUserId"));
        if (type == 1) {
            int stock = number(request.get("stock"), 0);
            jdbc.update("""
                    INSERT INTO commerce_seckill_voucher(voucher_id,stock,begin_time,end_time)
                    VALUES (?,?,?,?)
                    """,
                    id,
                    stock,
                    timestamp(request.get("beginTime"), null),
                    timestamp(request.get("endTime"), null)
            );
            // 只有 MySQL 事务真正提交后才开放 Redis 准入，避免 Redis 指向已回滚的 Voucher。
            afterCommit(() -> {
                inventory.initializeNewVoucher(id, stock);
                campaignMetadata.refreshAfterCommit(id);
            });
        }
        return new VoucherCreated(id);
    }

    /**
     * 更新 Voucher；秒杀活动先失效 Redis 权威副本，提交后按同一修订号重新发布。
     *
     * @param id Voucher ID
     * @param request 待更新字段
     * @param actorId 操作者用户 ID
     * @param admin 是否为管理员
     * @return 更新后的规范化 Voucher
     */
    @Transactional
    public VoucherView update(
            long id,
            Map<String, Object> request,
            long actorId,
            boolean admin
    ) {
        beginTransactionalMutation();
        Map<String, Object> existing = jdbc.queryForMap(
                "SELECT * FROM commerce_voucher WHERE id=?",
                id
        );
        long shopId = ((Number) existing.get("shop_id")).longValue();
        place.requireWriteAuthority(shopId, actorId, admin);
        validateUpdate(request, existing);
        boolean seckillVoucher = ((Number) existing.get("type")).intValue() == 1;
        long campaignRevision = -1L;
        if (seckillVoucher) {
            // 数据库修改前先删除 Redis 权威副本；事务失败或提交后刷新失败都会关闭准入，
            // 不会继续使用旧活动状态。
            campaignRevision = campaignMetadata.invalidateBeforeChange(id);
        }
        jdbc.update("""
                UPDATE commerce_voucher
                SET title=COALESCE(?,title),sub_title=COALESCE(?,sub_title),
                    rules=COALESCE(?,rules),pay_value=COALESCE(?,pay_value),
                    actual_value=COALESCE(?,actual_value),status=COALESCE(?,status),
                    version=version+1
                WHERE id=?
                """,
                request.get("title"), request.get("subTitle"), request.get("rules"),
                request.get("payValue"), request.get("actualValue"), request.get("status"), id
        );
        if (request.containsKey("stock")
                || request.containsKey("beginTime")
                || request.containsKey("endTime")) {
            jdbc.update("""
                    UPDATE commerce_seckill_voucher
                    SET stock=COALESCE(?,stock),
                        begin_time=COALESCE(?,begin_time),
                        end_time=COALESCE(?,end_time),
                        version=version+1
                    WHERE voucher_id=?
                    """,
                    request.containsKey("stock") ? number(request.get("stock"), 0) : null,
                    request.containsKey("beginTime") ? timestamp(request.get("beginTime"), null) : null,
                    request.containsKey("endTime") ? timestamp(request.get("endTime"), null) : null,
                    id
            );
        }
        if (seckillVoucher) {
            long committedRevision = campaignRevision;
            // 捕获本次失效修订号；较旧事务的 afterCommit 无法覆盖更新后的活动元数据。
            afterCommit(() -> campaignMetadata.refreshAfterCommit(id, committedRevision));
        }
        return jdbc.queryForObject("""
                SELECT v.*,s.stock,s.begin_time,s.end_time
                FROM commerce_voucher v
                LEFT JOIN commerce_seckill_voucher s ON s.voucher_id=v.id
                WHERE v.id=?
                """, this::voucherRow, id);
    }

    private long requiredLong(Map<String, Object> value, String key) {
        Long number = optionalLong(value.get(key));
        if (number == null || number <= 0) {
            throw new CommerceApiException("COMMERCE_INVALID_ARGUMENT", key + " is required", 400);
        }
        return number;
    }

    /** 为当前事务获取运行时写租约，并在事务完成后释放。 */
    private void beginTransactionalMutation() {
        if (runtimeLeases == null) return;
        CommerceRuntimeMutationLeases.MutationLease lease = runtimeLeases.acquireMutation();
        try {
            routing.requireRuntimeMutationActive();
            runtimeLeases.releaseAfterTransaction(lease);
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    private Long optionalLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** 校验创建字段；秒杀类型额外要求正库存和有效时间窗。 */
    private void validateCreate(Map<String, Object> request) {
        int type = number(request.get("type"), 0);
        int status = number(request.get("status"), 1);
        int payValue = requiredInteger(request.get("payValue"), "payValue");
        int actualValue = requiredInteger(request.get("actualValue"), "actualValue");
        validateBasic(type, status, text(request, "title"), payValue, actualValue);
        if (type != 1) {
            return;
        }
        int stock = requiredInteger(request.get("stock"), "stock");
        if (stock <= 0) {
            invalid("stock must be positive for a new seckill voucher");
        }
        Timestamp begin = requiredTimestamp(request.get("beginTime"), "beginTime");
        Timestamp end = requiredTimestamp(request.get("endTime"), "endTime");
        validateWindow(begin, end);
    }

    /**
     * 用请求字段覆盖数据库现值后整体校验；普通 Voucher 不得出现秒杀专属字段。
     */
    private void validateUpdate(Map<String, Object> request, Map<String, Object> existing) {
        int type = ((Number) existing.get("type")).intValue();
        int status = request.containsKey("status")
                ? requiredInteger(request.get("status"), "status")
                : ((Number) existing.get("status")).intValue();
        int payValue = request.containsKey("payValue")
                ? requiredInteger(request.get("payValue"), "payValue")
                : ((Number) existing.get("pay_value")).intValue();
        int actualValue = request.containsKey("actualValue")
                ? requiredInteger(request.get("actualValue"), "actualValue")
                : ((Number) existing.get("actual_value")).intValue();
        String title = request.containsKey("title")
                ? text(request, "title")
                : String.valueOf(existing.get("title"));
        validateBasic(type, status, title, payValue, actualValue);
        if (type != 1) {
            if (request.containsKey("stock")
                    || request.containsKey("beginTime")
                    || request.containsKey("endTime")) {
                invalid("Seckill fields cannot be set on a normal voucher");
            }
            return;
        }
        Map<String, Object> seckill = jdbc.queryForMap(
                "SELECT stock,begin_time,end_time FROM commerce_seckill_voucher WHERE voucher_id=?",
                existing.get("id")
        );
        int stock = request.containsKey("stock")
                ? requiredInteger(request.get("stock"), "stock")
                : ((Number) seckill.get("stock")).intValue();
        if (stock < 0) {
            invalid("stock cannot be negative");
        }
        int storedStock = ((Number) seckill.get("stock")).intValue();
        if (request.containsKey("stock") && stock != storedStock) {
            // 库存同时存在于 MySQL 和 Redis，普通更新无法原子维护两端，必须走带 fence 的维护流程。
            throw new CommerceApiException(
                    "COMMERCE_STOCK_ADJUSTMENT_REQUIRES_MAINTENANCE",
                    "Direct stock changes are disabled; use fenced inventory maintenance",
                    409
            );
        }
        Timestamp begin = request.containsKey("beginTime")
                ? requiredTimestamp(request.get("beginTime"), "beginTime")
                : timestamp(seckill.get("begin_time"), null);
        Timestamp end = request.containsKey("endTime")
                ? requiredTimestamp(request.get("endTime"), "endTime")
                : timestamp(seckill.get("end_time"), null);
        validateWindow(begin, end);
    }

    private void validateBasic(int type, int status, String title, int payValue, int actualValue) {
        if (type != 0 && type != 1) {
            invalid("type must be 0 or 1");
        }
        if (status < 1 || status > 3) {
            invalid("status must be 1, 2, or 3");
        }
        if (title == null || title.isBlank()) {
            invalid("title is required");
        }
        if (payValue < 0 || actualValue <= 0 || payValue > actualValue) {
            invalid("Voucher amounts are invalid");
        }
    }

    private int requiredInteger(Object value, String name) {
        if (!(value instanceof Number number)) {
            invalid(name + " is required");
        }
        return ((Number) value).intValue();
    }

    private Timestamp requiredTimestamp(Object value, String name) {
        if (value == null) {
            invalid(name + " is required");
        }
        return timestamp(value, null);
    }

    private void validateWindow(Timestamp begin, Timestamp end) {
        if (begin == null || end == null || !begin.before(end)) {
            invalid("Seckill endTime must be later than beginTime");
        }
    }

    private void invalid(String message) {
        throw new CommerceApiException("COMMERCE_INVALID_ARGUMENT", message, 400);
    }

    private String text(Map<String, Object> value, String key) {
        Object text = value.get(key);
        if (text == null || String.valueOf(text).isBlank()) {
            throw new CommerceApiException("COMMERCE_INVALID_ARGUMENT", key + " is required", 400);
        }
        return String.valueOf(text);
    }

    private Timestamp timestamp(Object value, Instant fallback) {
        return timestamp(value, fallback, ZoneId.systemDefault());
    }

    /**
     * 将时间戳、Epoch 毫秒或 ISO/本地业务时间文本统一转换为 JDBC Timestamp。
     *
     * @param value 输入时间
     * @param fallback 空值时使用的时间
     * @param businessZone 无时区本地时间采用的业务时区
     * @return 转换结果，输入和 fallback 均为空时返回 {@code null}
     */
    static Timestamp timestamp(Object value, Instant fallback, ZoneId businessZone) {
        if (value == null) {
            return fallback == null ? null : Timestamp.from(fallback);
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value instanceof java.util.Date date) {
            return Timestamp.from(date.toInstant());
        }
        if (value instanceof Number number) {
            return Timestamp.from(Instant.ofEpochMilli(number.longValue()));
        }
        String raw = String.valueOf(value).trim();
        try {
            return Timestamp.from(Instant.parse(raw));
        } catch (DateTimeParseException ignored) {
            // 不带 Z 的文本可能是 OffsetDateTime 或业务本地时间，继续尝试受支持格式。
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(raw).toInstant());
        } catch (DateTimeParseException ignored) {
            // 最后按调用方指定的业务时区解释无偏移量本地时间。
        }
        try {
            String normalized = raw.replace(' ', 'T');
            LocalDateTime local = LocalDateTime.parse(normalized);
            return Timestamp.from(local.atZone(businessZone).toInstant());
        } catch (DateTimeParseException invalid) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "Voucher time must be an ISO-8601 or yyyy-MM-dd HH:mm:ss value",
                    400
            );
        }
    }

    private VoucherView voucherRow(ResultSet row, int rowNumber) throws SQLException {
        return new VoucherView(row.getLong("id"), row.getLong("shop_id"), row.getString("title"),
                row.getString("sub_title"), row.getString("rules"), row.getInt("pay_value"),
                row.getInt("actual_value"), row.getInt("type"), row.getInt("status"), row.getInt("version"),
                row.getTimestamp("create_time"), row.getTimestamp("update_time"),
                row.getObject("stock", Integer.class), row.getTimestamp("begin_time"), row.getTimestamp("end_time"));
    }

    /**
     * 将 Redis 副作用推迟到 MySQL 提交成功之后；无活动事务时立即执行。
     *
     * @param action 提交后动作
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
