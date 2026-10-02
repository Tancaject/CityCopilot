package com.citycopilot.core.booking;

import com.citycopilot.contract.event.BookingChangedEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 浏览反馈与真实业务事实分开记账；派生特征不覆盖 Agent 的显式长期偏好。 */
@Service
public class BookingFeedbackService {
    private static final List<String> CLIENT_TYPES = List.of("VIEW", "CLICK", "FAVORITE", "NOT_INTERESTED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final BookingFeedbackPolicy policy;
    private final MeterRegistry metrics;

    public BookingFeedbackService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json,
                                  StringRedisTemplate redis, BookingFeedbackPolicy policy, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.redis = redis;
        this.policy = policy;
        this.metrics = metrics;
    }

    public record Impression(String id, long userId, List<String> candidateIds, String rankingVersion,
                             Instant issuedAt, Instant expiresAt) {}
    public record FeedbackRequest(String impressionId, String shopId, String eventType) {}
    public record FeedbackReceipt(String eventId, boolean duplicate) {}
    public record ShopFeature(String shopId, long views, long clicks, long favorites, long confirmed,
                              long used, long cancelled, long noShows, boolean negative, Instant lastOccurredAt) {}
    public record EffectiveRules(boolean enabled, int rawRetentionDays, int featureWindowDays, int cacheSeconds, long cleanupIntervalMs,
                                 String businessRetention, String negativeRetention, List<String> clientEventTypes,
                                 String impressionMeaning) {}
    public static final class Rejected extends RuntimeException {
        public final String code;
        public final int status;
        Rejected(String code, int status, String message) { super(message); this.code = code; this.status = status; }
    }

    public EffectiveRules rules() {
        return new EffectiveRules(policy.enabled(), policy.rawRetentionDays(), policy.rawRetentionDays(), policy.cacheSeconds(), policy.cleanupIntervalMs(),
                "WITH_BOOKING_RECORD_NO_AUTOMATIC_DELETION", "UNTIL_USER_CLEARS", CLIENT_TYPES,
                "ISSUED_CANDIDATES_REQUIRE_VIEW_EVENT_TO_COUNT_AS_VIEWED");
    }

    public Impression issueImpression(long userId, List<String> candidateIds, String rankingVersion) {
        return issueImpression(UUID.randomUUID().toString(), userId, candidateIds, rankingVersion);
    }

    /** 只由 Core 服务调用；先生成 ID 传递给报价，再登记实际返回的候选。 */
    public Impression issueImpression(String impressionId, long userId, List<String> candidateIds, String rankingVersion) {
        enabled();
        user(userId);
        uuid(impressionId);
        List<String> ids = shops(candidateIds);
        if (ids.isEmpty() || rankingVersion == null || rankingVersion.isBlank() || rankingVersion.length() > 64) {
            throw invalid("曝光必须包含候选店铺与有效排序版本");
        }
        Instant now = now();
        Impression impression = new Impression(impressionId, userId, ids, rankingVersion, now,
                now.plus(Duration.ofDays(policy.rawRetentionDays())));
        jdbc.update("INSERT INTO search_impression(id,user_id,candidate_ids,ranking_version,issued_at,expires_at) VALUES (?,?,?,?,?,?)",
                impression.id(), userId, encode(ids), rankingVersion, utc(now), utc(impression.expiresAt()));
        count("impression", "issued");
        return impression;
    }

    public FeedbackReceipt recordFeedback(long userId, FeedbackRequest request) {
        enabled();
        user(userId);
        if (request == null || request.eventType() == null || !CLIENT_TYPES.contains(request.eventType())) {
            throw invalid("只允许上报浏览、点击、收藏或不感兴趣，预约与履约结果由交易服务记录");
        }
        long shopId = id(request.shopId());
        uuid(request.impressionId());
        return transactions.execute(tx -> {
            Instant now = now();
            Impression impression = impression(request.impressionId());
            if (impression == null || impression.userId() != userId || !impression.candidateIds().contains(String.valueOf(shopId))
                    || !impression.expiresAt().isAfter(now)) {
                count("feedback", "invalid_attribution");
                throw new Rejected("BOOKING_FEEDBACK_INVALID_IMPRESSION", 409, "该曝光不存在、已过期或不属于当前用户与店铺，请刷新方案后再操作");
            }
            lockFeature(userId, shopId);
            String eventId = "client:" + impression.id() + ":" + shopId + ":" + request.eventType();
            boolean duplicate = !jdbc.queryForList("SELECT event_id FROM user_behavior_event WHERE event_id=? FOR UPDATE", String.class, eventId).isEmpty();
            if (!duplicate) {
                Instant expires = request.eventType().equals("NOT_INTERESTED") ? null : now.plus(Duration.ofDays(policy.rawRetentionDays()));
                jdbc.update("""
                        INSERT INTO user_behavior_event(event_id,user_id,shop_id,event_type,origin,impression_id,
                          ranking_version,candidate_position,attributed,occurred_at,expires_at)
                        VALUES (?,?,?,?,'CLIENT',?,?,?,?,?,?)
                        """, eventId, userId, shopId, request.eventType(), impression.id(), impression.rankingVersion(),
                        impression.candidateIds().indexOf(String.valueOf(shopId)) + 1, true, utc(now), utc(expires));
                rebuildLocked(userId, shopId, now);
            }
            countAfterCommit("feedback", duplicate ? "duplicate" : request.eventType().toLowerCase());
            return new FeedbackReceipt(eventId, duplicate);
        });
    }

    public void clearNegativeFeedback(long userId, String shopId) {
        user(userId);
        long shop = id(shopId);
        transactions.executeWithoutResult(tx -> {
            lockFeature(userId, shop);
            Instant now = now();
            // 保留同曝光幂等键；清除后重建和旧请求重投均不能恢复该负反馈。
            jdbc.update("""
                    UPDATE user_behavior_event SET active=FALSE,cleared_at=?,expires_at=?
                    WHERE user_id=? AND shop_id=? AND event_type='NOT_INTERESTED' AND origin='CLIENT' AND active=TRUE
                    """, utc(now), utc(now.plus(Duration.ofDays(policy.rawRetentionDays()))), userId, shop);
            rebuildLocked(userId, shop, now);
            countAfterCommit("feedback", "negative_cleared");
        });
    }

    public Map<String, ShopFeature> features(long userId, List<String> shopIds) {
        user(userId);
        List<String> ids = shops(shopIds);
        if (ids.isEmpty()) return Map.of();
        Map<String, ShopFeature> found = new LinkedHashMap<>();
        if (!policy.enabled()) return zeroFeatures(ids);
        List<String> keys = ids.stream().map(shop -> cacheKey(userId, id(shop))).toList();
        try {
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values != null) for (int i = 0; i < values.size(); i++) {
                if (values.get(i) != null) found.put(ids.get(i), json.readValue(values.get(i), ShopFeature.class));
            }
        } catch (DataAccessException | JacksonException error) {
            count("cache", "read_failure");
        }
        List<String> missing = ids.stream().filter(shop -> !found.containsKey(shop)).toList();
        if (!missing.isEmpty()) {
            List<Object> params = new ArrayList<>(); params.add(userId); params.addAll(missing.stream().map(BookingFeedbackService::id).toList());
            List<FeatureRow> rows = jdbc.query("SELECT *,refresh_after<=UTC_TIMESTAMP(3) AS needs_refresh FROM user_preference_feature WHERE user_id=? AND shop_id IN (" + marks(missing.size()) + ")",
                    (rs, row) -> new FeatureRow(featureRow(rs, row), rs.getBoolean("needs_refresh")), params.toArray());
            rows.forEach(row -> found.put(row.feature().shopId(), row.needsRefresh() ? rebuild(userId, row.feature().shopId()) : row.feature()));
            for (String shop : missing) {
                // 写入事实的事务同时维护派生行；冷启动不为每家候选扫描历史。
                ShopFeature feature = found.get(shop);
                if (feature == null) feature = new ShopFeature(shop, 0, 0, 0, 0, 0, 0, 0, false, null);
                found.put(shop, feature);
                try { redis.opsForValue().set(cacheKey(userId, id(shop)), encode(feature), Duration.ofSeconds(policy.cacheSeconds())); }
                catch (DataAccessException error) { count("cache", "write_failure"); }
            }
        }
        Map<String, ShopFeature> ordered = new LinkedHashMap<>();
        ids.forEach(shop -> ordered.put(shop, found.get(shop)));
        return Collections.unmodifiableMap(ordered);
    }

    public ShopFeature rebuild(long userId, String shopId) {
        user(userId);
        long shop = id(shopId);
        return transactions.execute(tx -> { lockFeature(userId, shop); return rebuildLocked(userId, shop, now()); });
    }

    void recordBusinessEvent(String eventId, String eventType, long version, Instant occurredAt, BookingChangedEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Inbox and booking feedback require one transaction");
        long userId = id(event.userId()), shopId = id(event.shopId());
        lockFeature(userId, shopId);
        // 同一用户/店铺已串行化；空投影不加间隙锁，避免不同预约同时插入时互相阻塞。
        List<Map<String, Object>> previous = jdbc.queryForList("SELECT * FROM booking_behavior_projection WHERE booking_id=?", event.bookingId());
        if (!previous.isEmpty() && (((Number) previous.get(0).get("user_id")).longValue() != userId
                || ((Number) previous.get(0).get("shop_id")).longValue() != shopId)) throw new IllegalArgumentException("Booking identity changed in event");
        // 早期报价允许任意128字符追踪串；无效归因不能阻止真实预约事件入账。
        String impressionId = canonicalImpressionId(event.impressionId());
        Impression impression = impression(impressionId);
        boolean attributed = impression != null && impression.userId() == userId
                && impression.candidateIds().contains(event.shopId()) && !impression.issuedAt().isAfter(occurredAt)
                && impression.expiresAt().isAfter(occurredAt);
        String ranking = attributed ? impression.rankingVersion() : null;
        Integer position = attributed ? impression.candidateIds().indexOf(event.shopId()) + 1 : null;
        // 首次已验证的预约归因留在业务投影中，曝光原始记录清理后仍可确认后续履约来源。
        if (!attributed && !previous.isEmpty() && truth(previous.get(0).get("attributed"))
                && Objects.equals(previous.get(0).get("impression_id"), impressionId)) {
            attributed = true;
            ranking = (String) previous.get(0).get("ranking_version");
            position = ((Number) previous.get(0).get("candidate_position")).intValue();
        }
        jdbc.update("""
                INSERT INTO user_behavior_event(event_id,user_id,shop_id,event_type,origin,impression_id,
                  ranking_version,candidate_position,attributed,booking_id,aggregate_version,occurred_at)
                VALUES (?,?,?,?,'COMMERCE',?,?,?,?,?,?,?)
                """, eventId, userId, shopId, eventType, impressionId, ranking, position, attributed,
                event.bookingId(), version, utc(occurredAt));
        jdbc.update("""
                INSERT INTO booking_behavior_projection(booking_id,user_id,shop_id,current_status,aggregate_version,
                  schedule_version,impression_id,ranking_version,candidate_position,attributed,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  current_status=IF(VALUES(aggregate_version)>aggregate_version,VALUES(current_status),current_status),
                  schedule_version=IF(VALUES(aggregate_version)>aggregate_version,VALUES(schedule_version),schedule_version),
                  occurred_at=IF(VALUES(aggregate_version)>aggregate_version,VALUES(occurred_at),occurred_at),
                  impression_id=IF(VALUES(attributed),VALUES(impression_id),impression_id),
                  ranking_version=IF(VALUES(attributed),VALUES(ranking_version),ranking_version),
                  candidate_position=IF(VALUES(attributed),VALUES(candidate_position),candidate_position),
                  attributed=attributed OR VALUES(attributed),
                  aggregate_version=GREATEST(aggregate_version,VALUES(aggregate_version))
                """, event.bookingId(), userId, shopId, event.status(), version, event.scheduleVersion(),
                impressionId, ranking, position, attributed, utc(occurredAt));
        rebuildLocked(userId, shopId, now());
        countAfterCommit("business", attributed ? "attributed" : "unattributed");
    }

    public int cleanupExpired() {
        if (!policy.enabled()) return 0;
        Instant now = now();
        List<Map<String, Object>> pairs = jdbc.queryForList("""
                SELECT user_id,shop_id FROM user_behavior_event WHERE expires_at<=?
                UNION SELECT user_id,shop_id FROM user_preference_feature WHERE refresh_after<=?
                ORDER BY user_id,shop_id LIMIT 500
                """, utc(now), utc(now));
        int removed = 0;
        for (Map<String, Object> pair : pairs) {
            long userId = ((Number) pair.get("user_id")).longValue(), shopId = ((Number) pair.get("shop_id")).longValue();
            removed += Objects.requireNonNull(transactions.execute(tx -> {
                lockFeature(userId, shopId);
                int deleted = jdbc.update("DELETE FROM user_behavior_event WHERE user_id=? AND shop_id=? AND origin='CLIENT' AND expires_at<=?", userId, shopId, utc(now));
                rebuildLocked(userId, shopId, now);
                return deleted;
            }));
        }
        removed += jdbc.update("DELETE FROM search_impression WHERE expires_at<=? LIMIT 1000", utc(now));
        return removed;
    }

    private ShopFeature rebuildLocked(long userId, long shopId, Instant now) {
        Aggregate aggregate = jdbc.queryForObject("""
                SELECT SUM(event_type='VIEW') AS views,SUM(event_type='CLICK') AS clicks,
                  SUM(event_type='FAVORITE') AS favorites,SUM(event_type='CONFIRMED') AS confirmed,
                  SUM(event_type='USED') AS used,SUM(event_type='CANCELLED') AS cancelled,
                  SUM(event_type='NO_SHOW') AS no_shows,COALESCE(MAX(event_type='NOT_INTERESTED' AND active),0) AS negative,
                  MAX(occurred_at) AS last_occurred_at,
                  MIN(CASE WHEN event_type<>'NOT_INTERESTED' THEN occurred_at END) AS first_occurred_at
                FROM user_behavior_event WHERE user_id=? AND shop_id=? AND (expires_at IS NULL OR expires_at>?)
                  AND (occurred_at>? OR (event_type='NOT_INTERESTED' AND active)) FOR UPDATE
                """, (rs, row) -> new Aggregate(new ShopFeature(String.valueOf(shopId), rs.getLong("views"), rs.getLong("clicks"),
                rs.getLong("favorites"), rs.getLong("confirmed"), rs.getLong("used"), rs.getLong("cancelled"),
                rs.getLong("no_shows"), rs.getBoolean("negative"), instant(rs, "last_occurred_at")),
                instant(rs, "first_occurred_at")), userId, shopId, utc(now), utc(now.minus(Duration.ofDays(policy.rawRetentionDays()))));
        ShopFeature feature = aggregate.feature();
        Instant refreshAfter = aggregate.firstOccurredAt() == null ? null : aggregate.firstOccurredAt().plus(Duration.ofDays(policy.rawRetentionDays()));
        jdbc.update("""
                UPDATE user_preference_feature SET views=?,clicks=?,favorites=?,confirmed=?,used=?,cancelled=?,
                  no_shows=?,negative=?,last_occurred_at=?,rebuilt_at=?,refresh_after=? WHERE user_id=? AND shop_id=?
                """, feature.views(), feature.clicks(), feature.favorites(), feature.confirmed(), feature.used(),
                feature.cancelled(), feature.noShows(), feature.negative(), utc(feature.lastOccurredAt()), utc(now), utc(refreshAfter), userId, shopId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { redis.delete(cacheKey(userId, shopId)); }
                catch (DataAccessException error) { count("cache", "invalidate_failure"); }
            }
        });
        return feature;
    }

    private void lockFeature(long userId, long shopId) {
        jdbc.update("INSERT INTO user_preference_feature(user_id,shop_id) VALUES (?,?) ON DUPLICATE KEY UPDATE user_id=user_id", userId, shopId);
    }

    private Impression impression(String impressionId) {
        if (impressionId == null) return null;
        List<Impression> rows = jdbc.query("SELECT * FROM search_impression WHERE id=?", (rs, row) -> new Impression(
                rs.getString("id"), rs.getLong("user_id"), decodeIds(rs.getString("candidate_ids")), rs.getString("ranking_version"),
                instant(rs, "issued_at"), instant(rs, "expires_at")), impressionId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private ShopFeature featureRow(ResultSet rs, int row) throws SQLException {
        return new ShopFeature(String.valueOf(rs.getLong("shop_id")), rs.getLong("views"), rs.getLong("clicks"),
                rs.getLong("favorites"), rs.getLong("confirmed"), rs.getLong("used"), rs.getLong("cancelled"),
                rs.getLong("no_shows"), rs.getBoolean("negative"), instant(rs, "last_occurred_at"));
    }
    private record FeatureRow(ShopFeature feature, boolean needsRefresh) {}
    private record Aggregate(ShopFeature feature, Instant firstOccurredAt) {}
    private Map<String, ShopFeature> zeroFeatures(List<String> ids) {
        Map<String, ShopFeature> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, new ShopFeature(id, 0, 0, 0, 0, 0, 0, 0, false, null)));
        return result;
    }
    private List<String> shops(List<String> ids) {
        if (ids == null || ids.size() > 50) throw invalid("一次最多处理50家候选店铺");
        return ids.stream().map(value -> String.valueOf(id(value))).distinct().toList();
    }
    private List<String> decodeIds(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (JacksonException error) { throw new IllegalStateException("Invalid persisted impression candidates", error); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException error) { throw new IllegalStateException("Cannot serialize booking feedback", error); }
    }
    private void enabled() { if (!policy.enabled()) throw new Rejected("BOOKING_FEEDBACK_DISABLED", 503, "预约行为反馈尚未开放，请继续使用普通预约功能"); }
    private static void user(long userId) { if (userId <= 0) throw new Rejected("BOOKING_AUTH_REQUIRED", 401, "请先登录再管理预约反馈"); }
    private static long id(String value) {
        try { long id = Long.parseLong(value); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw invalid("店铺或用户标识必须是正整数");
    }
    private static void uuid(String value) {
        if (canonicalImpressionId(value) != null) return;
        throw invalid("曝光标识无效，请刷新方案后重试");
    }
    private static String canonicalImpressionId(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value) ? value : null; }
        catch (IllegalArgumentException ignored) { return null; }
    }
    private static Rejected invalid(String message) { return new Rejected("BOOKING_FEEDBACK_INVALID_REQUEST", 400, message); }
    private Instant now() { return Objects.requireNonNull(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class)).toInstant(ZoneOffset.UTC); }
    private static LocalDateTime utc(Instant time) { return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String name) throws SQLException { LocalDateTime time = rs.getObject(name, LocalDateTime.class); return time == null ? null : time.toInstant(ZoneOffset.UTC); }
    private static String marks(int size) { return String.join(",", Collections.nCopies(size, "?")); }
    private static String cacheKey(long userId, long shopId) { return "booking:features:v1:" + userId + ":" + shopId; }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0; }
    private void count(String action, String outcome) { metrics.counter("citycopilot.booking.feedback", "action", action, "outcome", outcome).increment(); }
    private void countAfterCommit(String action, String outcome) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { count(action, outcome); }
        });
    }
}
