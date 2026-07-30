package com.citycopilot.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CommerceVoucherService {
    private final JdbcTemplate jdbc;
    private final PlaceAuthorityClient place;
    private final CommerceOrderIdGenerator ids;
    private final CommerceRedisInventoryBootstrap inventory;

    public CommerceVoucherService(
            JdbcTemplate jdbc,
            PlaceAuthorityClient place,
            CommerceOrderIdGenerator ids,
            CommerceRedisInventoryBootstrap inventory
    ) {
        this.jdbc = jdbc;
        this.place = place;
        this.ids = ids;
        this.inventory = inventory;
    }

    public List<Map<String, Object>> list(long shopId) {
        return jdbc.queryForList("""
                SELECT v.*,s.stock,s.begin_time,s.end_time
                FROM commerce_voucher v
                LEFT JOIN commerce_seckill_voucher s ON s.voucher_id=v.id
                WHERE v.shop_id=? AND v.status=1
                ORDER BY v.id
                """, shopId).stream().map(this::normalize).toList();
    }

    public List<Map<String, Object>> adminList(Long shopId, String keyword, Integer status) {
        return jdbc.queryForList("""
                SELECT v.*,s.stock,s.begin_time,s.end_time
                FROM commerce_voucher v
                LEFT JOIN commerce_seckill_voucher s ON s.voucher_id=v.id
                WHERE (? IS NULL OR v.shop_id=?)
                  AND (? IS NULL OR v.status=?)
                  AND (? IS NULL OR v.title LIKE CONCAT('%',?,'%'))
                ORDER BY v.id DESC
                LIMIT 100
                """, shopId, shopId, status, status, keyword, keyword)
                .stream().map(this::normalize).toList();
    }

    @Transactional
    public long create(Map<String, Object> request, long actorId, boolean admin) {
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
            afterCommit(() -> inventory.initializeNewVoucher(id, stock));
        }
        return id;
    }

    @Transactional
    public Map<String, Object> update(
            long id,
            Map<String, Object> request,
            long actorId,
            boolean admin
    ) {
        Map<String, Object> existing = jdbc.queryForMap(
                "SELECT * FROM commerce_voucher WHERE id=?",
                id
        );
        long shopId = ((Number) existing.get("shop_id")).longValue();
        place.requireWriteAuthority(shopId, actorId, admin);
        validateUpdate(request, existing);
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
        return normalize(jdbc.queryForMap("""
                SELECT v.*,s.stock,s.begin_time,s.end_time
                FROM commerce_voucher v
                LEFT JOIN commerce_seckill_voucher s ON s.voucher_id=v.id
                WHERE v.id=?
                """, id));
    }

    private long requiredLong(Map<String, Object> value, String key) {
        Long number = optionalLong(value.get(key));
        if (number == null || number <= 0) {
            throw new CommerceApiException("COMMERCE_INVALID_ARGUMENT", key + " is required", 400);
        }
        return number;
    }

    private Long optionalLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

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
            try {
                return Timestamp.from(OffsetDateTime.parse(raw).toInstant());
            } catch (DateTimeParseException ignoredOffset) {
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
        }
    }

    private Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        rename(result, "shop_id", "shopId");
        rename(result, "sub_title", "subTitle");
        rename(result, "pay_value", "payValue");
        rename(result, "actual_value", "actualValue");
        rename(result, "begin_time", "beginTime");
        rename(result, "end_time", "endTime");
        rename(result, "create_time", "createTime");
        rename(result, "update_time", "updateTime");
        return result;
    }

    private void rename(Map<String, Object> row, String from, String to) {
        if (row.containsKey(from)) row.put(to, row.remove(from));
    }

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
