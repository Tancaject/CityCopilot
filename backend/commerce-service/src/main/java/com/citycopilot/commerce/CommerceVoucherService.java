package com.citycopilot.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CommerceVoucherService {
    private final JdbcTemplate jdbc;
    private final PlaceAuthorityClient place;

    public CommerceVoucherService(JdbcTemplate jdbc, PlaceAuthorityClient place) {
        this.jdbc = jdbc;
        this.place = place;
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
        Long requestedId = optionalLong(request.get("id"));
        long id = requestedId == null
                ? jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM commerce_voucher", Long.class)
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
            jdbc.update("""
                    INSERT INTO commerce_seckill_voucher(voucher_id,stock,begin_time,end_time)
                    VALUES (?,?,?,?)
                    """,
                    id,
                    number(request.get("stock"), 0),
                    timestamp(request.get("beginTime"), Instant.now().minusSeconds(60)),
                    timestamp(request.get("endTime"), Instant.now().plusSeconds(86400))
            );
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
        if (request.containsKey("stock")) {
            jdbc.update("""
                    UPDATE commerce_seckill_voucher
                    SET stock=?,version=version+1
                    WHERE voucher_id=?
                    """, number(request.get("stock"), 0), id);
        }
        return normalize(jdbc.queryForMap("SELECT * FROM commerce_voucher WHERE id=?", id));
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

    private String text(Map<String, Object> value, String key) {
        Object text = value.get(key);
        if (text == null || String.valueOf(text).isBlank()) {
            throw new CommerceApiException("COMMERCE_INVALID_ARGUMENT", key + " is required", 400);
        }
        return String.valueOf(text);
    }

    private Timestamp timestamp(Object value, Instant fallback) {
        if (value == null) return Timestamp.from(fallback);
        return Timestamp.from(Instant.parse(String.valueOf(value)));
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
}
