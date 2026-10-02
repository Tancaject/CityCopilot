package com.citycopilot.commerce.booking;

import com.citycopilot.commerce.CommerceApiException;
import com.citycopilot.commerce.PlaceAuthorityClient;
import com.citycopilot.contract.booking.BookingContracts.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Resource publishing locks the resource before its slots; booking allocation keeps its own transaction. */
@Service
public class BookingResourceService {
    private static final Set<String> RESOURCE_STATES = Set.of("OPEN", "STOP_NEW", "UNAVAILABLE");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PlaceAuthorityClient place;
    private final BookingPolicy policy;

    public BookingResourceService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                  PlaceAuthorityClient place, BookingPolicy policy) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.place = place;
        this.policy = policy;
    }

    public Rules rules() {
        return new Rules(policy.courtSlotMinutes(), policy.publishingDays(), policy.holdSeconds(),
                policy.maxActiveHolds(), policy.maxFutureBookings(), policy.maxDiningPartySize(),
                policy.cancellationMinutes(), policy.checkinEarlyMinutes(), policy.reminderMinutes(),
                policy.defaultTimezone(), now(), policy.newBookingsEnabled());
    }

    public List<ResourceView> resources(long shopId) {
        positive(shopId, "shopId");
        return jdbc.queryForList("SELECT * FROM commerce_booking_resource WHERE shop_id=? AND status<>'UNAVAILABLE' ORDER BY id", shopId)
                .stream().map(this::resourceView).toList();
    }

    public ResourceView resource(long resourceId) {
        return resourceView(resource(resourceId, false));
    }

    public SlotView slot(long slotId) {
        Map<String, Object> slot = slot(slotId, false);
        Map<String, Object> resource = resource(number(slot, "resource_id"), false);
        return slotView(slot, now(), "OPEN".equals(text(resource, "status")));
    }

    public List<ResourceView> merchantResources(long shopId, long actorId, boolean platformAdmin) {
        authority(shopId, actorId, platformAdmin);
        return jdbc.queryForList("SELECT * FROM commerce_booking_resource WHERE shop_id=? ORDER BY id", shopId)
                .stream().map(this::resourceView).toList();
    }

    public ResourceView createResource(long shopId, long actorId, boolean platformAdmin, ResourceRequest request) {
        requireNewBookings();
        authority(shopId, actorId, platformAdmin);
        validateResource(request);
        String timezone = request.timezone() == null ? policy.defaultTimezone() : request.timezone();
        long id = insertId("INSERT INTO commerce_booking_resource(shop_id,kind,name,timezone,max_party_size,status) VALUES (?,?,?,?,?,?)",
                shopId, request.kind(), request.name().trim(), timezone, request.maxPartySize(), state(request.status()));
        return resourceView(resource(id, false));
    }

    public ResourceView updateResource(long resourceId, long actorId, boolean platformAdmin, ResourceRequest request) {
        Map<String, Object> located = resource(resourceId, false);
        authority(number(located, "shop_id"), actorId, platformAdmin);
        validateResource(request);
        requireVersion(request.expectedVersion());
        return transactions.execute(tx -> {
            Map<String, Object> current = resource(resourceId, true);
            version(current, request.expectedVersion());
            String timezone = request.timezone() == null ? text(current, "timezone") : request.timezone();
            if (!request.kind().equals(text(current, "kind")) || !timezone.equals(text(current, "timezone"))) {
                fail("BOOKING_RESOURCE_IMMUTABLE", "资源类型与时区不可修改，请创建新的资源", 409);
            }
            changed(jdbc.update("UPDATE commerce_booking_resource SET name=?,max_party_size=?,status=?,version=version+1 WHERE id=? AND version=?",
                    request.name().trim(), request.maxPartySize(), state(request.status()), resourceId, request.expectedVersion()));
            return resourceView(resource(resourceId, false));
        });
    }

    public SlotView saveSlot(long resourceId, long actorId, boolean platformAdmin, SlotRequest request) {
        Map<String, Object> located = resource(resourceId, false);
        authority(number(located, "shop_id"), actorId, platformAdmin);
        if (request == null || request.startAt() == null || request.endAt() == null || request.capacity() == null) invalid("需要时段起止时间和容量");
        String status = state(request.status());
        return transactions.execute(tx -> {
            Map<String, Object> resource = resource(resourceId, true);
            Map<String, Object> existing = request.slotId() == null ? null : slot(id(request.slotId(), "slotId"), true);
            Instant currentTime = now();
            validateSlot(resource, request, currentTime);
            if (existing != null) {
                if (number(existing, "resource_id") != resourceId) notFound("时段不属于该资源");
                requireVersion(request.expectedVersion());
                version(existing, request.expectedVersion());
                if (number(existing, "occupied") > 0 && (!request.startAt().equals(instant(existing.get("start_at")))
                        || !request.endAt().equals(instant(existing.get("end_at"))))) {
                    fail("BOOKING_SLOT_OCCUPIED", "该时段已有预约，不能修改起止时间", 409);
                }
                if (request.capacity() < number(existing, "occupied")) fail("BOOKING_SLOT_OCCUPIED", "容量不能小于已占用名额", 409);
            } else {
                requireNewBookings();
            }
            Long overlaps = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_booking_slot WHERE resource_id=? AND start_at<? AND end_at>? AND id<>?",
                    Long.class, resourceId, time(request.endAt()), time(request.startAt()), existing == null ? -1L : number(existing, "id"));
            if (overlaps != null && overlaps > 0) fail("BOOKING_SLOT_OVERLAP", "该资源已有重叠时段，请调整起止时间", 409);
            long slotId;
            if (existing == null) {
                slotId = insertId("INSERT INTO commerce_booking_slot(resource_id,start_at,end_at,capacity,status) VALUES (?,?,?,?,?)",
                        resourceId, time(request.startAt()), time(request.endAt()), request.capacity(), status);
            } else {
                slotId = number(existing, "id");
                changed(jdbc.update("UPDATE commerce_booking_slot SET start_at=?,end_at=?,capacity=?,status=?,version=version+1 WHERE id=? AND version=?",
                        time(request.startAt()), time(request.endAt()), request.capacity(), status, slotId, request.expectedVersion()));
            }
            return slotView(slot(slotId, false), currentTime, "OPEN".equals(text(resource, "status")));
        });
    }

    public List<SlotView> slots(long resourceId, Instant dateFrom, Instant dateTo) {
        Map<String, Object> resource = resource(resourceId, false);
        Instant from = dateFrom == null ? now() : dateFrom;
        Instant to = dateTo == null ? from.plus(Duration.ofDays(policy.publishingDays())) : dateTo;
        window(from, to);
        Instant current = now();
        return jdbc.queryForList("SELECT * FROM commerce_booking_slot WHERE resource_id=? AND start_at>=? AND start_at<? ORDER BY start_at",
                resourceId, time(from), time(to)).stream().map(row -> slotView(row, current, "OPEN".equals(text(resource, "status")))).toList();
    }

    private void validateResource(ResourceRequest request) {
        if (request == null || request.kind() == null || !Set.of("COURT", "DINING_POOL").contains(request.kind())) invalid("资源类型必须为 COURT 或 DINING_POOL");
        if (request.name() == null || request.name().isBlank() || request.name().trim().length() > 160) invalid("资源名称必须为 1～160 个字符");
        if (request.maxPartySize() == null || request.maxPartySize() < 1 || request.maxPartySize() > 1000) invalid("资源人数上限必须为 1～1000");
        try { ZoneId.of(request.timezone() == null ? policy.defaultTimezone() : request.timezone()); }
        catch (DateTimeException badZone) { invalid("时区必须为有效的 IANA 时区名称"); }
        state(request.status());
    }

    private void validateSlot(Map<String, Object> resource, SlotRequest request, Instant current) {
        if (!request.endAt().isAfter(request.startAt()) || request.capacity() < 1 || request.capacity() > 100000) invalid("时段结束须晚于开始，容量必须为 1～100000");
        if (!request.startAt().isAfter(current) || request.endAt().isAfter(current.plus(Duration.ofDays(policy.publishingDays())))) invalid("排期必须位于未来 " + policy.publishingDays() + " 天内");
        if ("COURT".equals(text(resource, "kind"))) {
            ZonedDateTime local = request.startAt().atZone(ZoneId.of(text(resource, "timezone")));
            if (request.capacity() != 1 || !request.endAt().equals(request.startAt().plusSeconds(policy.courtSlotMinutes() * 60L))
                    || local.getMinute() != 0 || local.getSecond() != 0 || local.getNano() != 0) {
                invalid("具体场地容量必须为 1，按当地整点发布 " + policy.courtSlotMinutes() + " 分钟时段");
            }
        }
    }

    private ResourceView resourceView(Map<String, Object> row) {
        return new ResourceView(text(row, "id"), text(row, "shop_id"), text(row, "kind"), text(row, "name"), text(row, "timezone"), integer(row, "max_party_size"), text(row, "status"), number(row, "version"));
    }

    private SlotView slotView(Map<String, Object> row, Instant current, boolean resourceOpen) {
        int capacity = integer(row, "capacity");
        int occupied = integer(row, "occupied");
        int available = resourceOpen && "OPEN".equals(text(row, "status")) && current.isBefore(instant(row.get("start_at"))) ? capacity - occupied : 0;
        return new SlotView(text(row, "id"), text(row, "resource_id"), instant(row.get("start_at")), instant(row.get("end_at")), capacity, occupied, available, text(row, "status"), number(row, "version"));
    }

    private Map<String, Object> resource(long id, boolean lock) {
        positive(id, "resourceId");
        return single("SELECT * FROM commerce_booking_resource WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    private Map<String, Object> slot(long id, boolean lock) {
        positive(id, "slotId");
        return single("SELECT * FROM commerce_booking_slot WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) notFound("预约、资源或时段不存在");
        return rows.get(0);
    }

    private Instant now() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class).toInstant(ZoneOffset.UTC); }
    private static LocalDateTime time(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        if (value instanceof Instant instant) return instant;
        return Instant.parse(String.valueOf(value));
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static int integer(Map<String, Object> row, String key) { return Math.toIntExact(number(row, key)); }
    private static String text(Map<String, Object> row, String key) { return String.valueOf(row.get(key)); }
    private long insertId(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        int affected = jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, key);
        changed(affected);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private void authority(long shopId, long actorId, boolean platformAdmin) { positive(actorId, "actorId"); positive(shopId, "shopId"); place.requireWriteAuthority(shopId, actorId, platformAdmin); }
    private void requireNewBookings() { if (!policy.newBookingsEnabled()) fail("BOOKING_NEW_DISABLED", "暂不接受新预约；已有预约仍可查询和处理", 503); }
    private void window(Instant from, Instant to) { if (!to.isAfter(from) || to.isAfter(from.plus(Duration.ofDays(policy.publishingDays())))) invalid("查询时间窗口必须为正且不超过 " + policy.publishingDays() + " 天"); }
    private static void version(Map<String, Object> row, Long expected) { if (expected == null || number(row, "version") != expected) fail("BOOKING_VERSION_CONFLICT", "预约或资源已变化，请刷新后确认当前内容", 409); }
    private static void requireVersion(Long value) { if (value == null || value < 0) invalid("expectedVersion 必须是非负版本号"); }
    private static String state(String value) { String state = value == null ? "OPEN" : value; if (!RESOURCE_STATES.contains(state)) invalid("状态必须为 OPEN、STOP_NEW 或 UNAVAILABLE"); return state; }
    private static long id(String value, String name) { try { long id = Long.parseLong(value); positive(id, name); return id; } catch (NumberFormatException | NullPointerException invalid) { invalid(name + " 必须为正整数 ID"); return 0; } }
    private static void positive(long value, String field) { if (value <= 0) invalid(field + " 必须为正整数"); }
    private static void changed(int affected) { if (affected != 1) throw new IllegalStateException("Booking mutation did not affect exactly one row"); }
    private static void notFound(String message) { fail("BOOKING_NOT_FOUND", message, 404); }
    private static void invalid(String message) { fail("BOOKING_INVALID_ARGUMENT", message, 400); }
    private static void fail(String code, String message, int status) { throw new CommerceApiException(code, message, status); }
}
