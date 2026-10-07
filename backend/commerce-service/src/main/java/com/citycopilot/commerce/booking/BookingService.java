package com.citycopilot.commerce.booking;

import com.citycopilot.contract.event.EventEnvelope;

import com.citycopilot.commerce.CommerceApiException;
import com.citycopilot.commerce.PlaceAuthorityClient;
import com.citycopilot.commerce.pricing.BookingPricingService;
import com.citycopilot.contract.booking.BookingContracts.*;
import com.citycopilot.contract.booking.PricingContracts.QuoteView;
import com.citycopilot.contract.event.BookingChangedEvent;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Booking lifecycle commands own the allocation, pricing, idempotency and Outbox transaction. */
@Service
public class BookingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final PlaceAuthorityClient place;
    private final BookingPolicy policy;
    private final BookingPricingService pricing;

    @Autowired
    public BookingService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json,
                          PlaceAuthorityClient place, BookingPolicy policy, BookingPricingService pricing) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.place = place;
        this.policy = policy;
        this.pricing = pricing;
    }

    public MutationResult hold(long userId, String key, HoldRequest request) {
        positive(userId, "userId");
        requiredKey(key);
        if (request == null) invalid("缺少暂留请求");
        bounded(request.source(), 32, "source");
        bounded(request.requestId(), 128, "requestId");
        String hash = requestHash("HOLD", request);
        MutationResult replay = replay(userId, key, hash);
        if (replay != null) return replay;
        requireNewBookings();
        Target target = target(request.resourceId(), request.slotId(), request.partySize(), request.quoteId(), userId);
        long resourceId = target.resourceId();
        long slotId = target.slotId();
        // Expired bookings use separate transactions so their slot locks never leak into a new allocation.
        for (String expired : jdbc.queryForList("SELECT id FROM commerce_booking WHERE user_id=? AND status='HELD' AND hold_expires_at<=UTC_TIMESTAMP(3)", String.class, userId)) {
            expireOne(expired);
        }
        return command(userId, userId, key, "HOLD", hash, () -> {
            Map<String, Object> resource = resource(resourceId, true);
            QuoteView quote = lockQuote(request.quoteId(), userId, resourceId);
            Map<String, Object> slot = slot(slotId, true);
            pricing.lockCoupons(null, quote);
            Instant current = now();
            int units = units(resource, target.partySize());
            available(resource, slot, resourceId, current);
            quota(userId, current);
            if (quote != null) pricing.validateQuote(quote, userId, String.valueOf(resourceId), String.valueOf(slotId), target.partySize(), null, null, current);
            take(slotId, units);
            String bookingId = UUID.randomUUID().toString();
            Instant expires = current.plusSeconds(policy.holdSeconds());
            if (expires.isAfter(instant(slot.get("start_at")))) expires = instant(slot.get("start_at"));
            if (quote != null && pricing.earliestCouponExpiry(quote) != null && expires.isAfter(pricing.earliestCouponExpiry(quote))) {
                expires = pricing.earliestCouponExpiry(quote);
            }
            changed(jdbc.update("""
                    INSERT INTO commerce_booking(id,user_id,shop_id,resource_id,slot_id,resource_name,resource_kind,
                    party_size,units,status,start_at,end_at,hold_expires_at,price_snapshot,source,request_id)
                    VALUES (?,?,?,?,?,?,?,?,?,'HELD',?,?,?,?,?,?)
                    """, bookingId, userId, number(resource, "shop_id"), resourceId, slotId, text(resource, "name"), text(resource, "kind"),
                    target.partySize(), units, slot.get("start_at"), slot.get("end_at"), time(expires), quote == null ? onSite() : write(pricing.snapshot(quote)),
                    quote == null ? request.source() : quote.source(), quote == null ? request.requestId() : quote.requestId()));
            if (quote != null) pricing.applyQuote(quote, bookingId, current);
            Map<String, Object> booking = row(bookingId, false);
            event(booking, "held", userId);
            return result(booking, userId, false, "BOOKING_HELD", 201, "名额已暂留，请在截止前确认");
        });
    }

    public MutationResult confirm(long userId, String bookingId, String key, ActionRequest request) {
        return change(userId, false, false, bookingId, key, "CONFIRM", request, () -> {
            Map<String, Object> booking = row(bookingId, true);
            Map<String, Object> resource = resource(number(booking, "resource_id"), true);
            Map<String, Object> slot = slot(number(booking, "slot_id"), true);
            pricing.lockCoupons(bookingId, null);
            Instant current = now();
            version(booking, request.expectedVersion());
            requireState(booking, "HELD");
            if (!current.isBefore(instant(booking.get("hold_expires_at")))) {
                terminal(booking, "HOLD_EXPIRED", "BOOKING_HOLD_EXPIRED", userId, true);
                return result(row(bookingId, false), userId, false, "BOOKING_HOLD_EXPIRED", 409, "暂留已到期，名额已释放，请重新选择");
            }
            if ("UNAVAILABLE".equals(text(resource, "status")) || "UNAVAILABLE".equals(text(slot, "status"))) {
                terminal(booking, "CANCELLED", "BOOKING_RESOURCE_UNAVAILABLE", userId, true);
                return result(row(bookingId, false), userId, false, "BOOKING_RESOURCE_UNAVAILABLE", 409, "商家无法接待，该暂留已取消并释放名额");
            }
            if (!pricing.heldCouponsValid(bookingId, current)) {
                terminal(booking, "CANCELLED", "BOOKING_COUPON_UNAVAILABLE", userId, true);
                return result(row(bookingId, false), userId, false, "BOOKING_COUPON_UNAVAILABLE", 409, "所选优惠资格已失效，该暂留已取消并释放名额");
            }
            long schedule = number(booking, "schedule_version") + 1;
            changed(jdbc.update("UPDATE commerce_booking SET status='CONFIRMED',version=version+1,schedule_version=?,checkin_token_hash=? WHERE id=? AND status='HELD'",
                    schedule, digest(token(bookingId, schedule)), bookingId));
            Map<String, Object> updated = row(bookingId, false);
            reminder(updated, current);
            event(updated, "confirmed", userId);
            return result(updated, userId, false, "BOOKING_CONFIRMED", 200, "预约已确认，实际消费到店结算");
        });
    }

    public MutationResult cancel(long actorId, boolean merchant, boolean platformAdmin, String bookingId, String key, ActionRequest request) {
        if (merchant && (request == null || request.reason() == null || request.reason().isBlank())) invalid("商家取消必须填写原因");
        return change(actorId, merchant, platformAdmin, bookingId, key, merchant ? "MERCHANT_CANCEL" : "CANCEL", request, () -> {
            Map<String, Object> booking = row(bookingId, true);
            resource(number(booking, "resource_id"), true);
            slot(number(booking, "slot_id"), true);
            pricing.lockCoupons(bookingId, null);
            Instant current = now();
            version(booking, request.expectedVersion());
            if (!Set.of("HELD", "CONFIRMED").contains(text(booking, "status"))) conflict("该预约不能取消");
            if ("HELD".equals(text(booking, "status")) && !current.isBefore(instant(booking.get("hold_expires_at")))) {
                terminal(booking, "HOLD_EXPIRED", "BOOKING_HOLD_EXPIRED", actorId, true);
                return result(row(bookingId, false), actorId, merchant, "BOOKING_HOLD_EXPIRED", 409, "暂留已到期，名额已释放");
            }
            if (merchant) {
                if (!current.isBefore(instant(booking.get("start_at")))) fail("BOOKING_CUTOFF_PASSED", "预约已经开始，不能按未来预约取消", 409);
            } else if ("CONFIRMED".equals(text(booking, "status"))) cutoff(booking, current);
            String reason = merchant ? request.reason().trim() : "USER_CANCELLED";
            terminal(booking, "CANCELLED", reason, actorId, true);
            Map<String, Object> updated = row(bookingId, false);
            if (merchant) notice(updated, "MERCHANT_CANCELLED", "商家取消预约：" + reason, current, true);
            return result(updated, actorId, merchant, "BOOKING_CANCELLED", 200, "预约已取消，名额已释放");
        });
    }

    public MutationResult reschedule(long userId, String bookingId, String key, RescheduleRequest request) {
        if (request == null) invalid("缺少改期请求");
        requireVersion(request.expectedVersion());
        Map<String, Object> located = own(bookingId, userId);
        String hash = requestHash("RESCHEDULE:" + bookingId, request);
        MutationResult existing = replay(userId, key, hash);
        if (existing != null) return existing;
        Target target = target(request.resourceId(), request.slotId(), request.partySize(), request.quoteId(), userId);
        long targetResourceId = target.resourceId();
        long targetSlotId = target.slotId();
        return command(userId, number(located, "user_id"), key, "RESCHEDULE", hash, () -> {
            Map<String, Object> booking = row(bookingId, true);
            version(booking, request.expectedVersion());
            requireState(booking, "CONFIRMED");
            Map<Long, Map<String, Object>> resources = lockResources(number(booking, "resource_id"), targetResourceId);
            Map<String, Object> targetResource = resources.get(targetResourceId);
            if (number(targetResource, "shop_id") != number(booking, "shop_id")
                    || !text(targetResource, "kind").equals(text(booking, "resource_kind"))) {
                fail("BOOKING_SHOP_MISMATCH", "改期只能选择同店同业务类型的资源", 409);
            }
            QuoteView quote = lockQuote(request.quoteId(), userId, targetResourceId);
            Map<Long, Map<String, Object>> slots = lockSlots(number(booking, "slot_id"), targetSlotId);
            Map<String, Object> targetSlot = slots.get(targetSlotId);
            pricing.lockCoupons(bookingId, quote);
            Instant current = now();
            cutoff(booking, current);
            available(targetResource, targetSlot, targetResourceId, current);
            int nextUnits = units(targetResource, target.partySize());
            if (quote != null) pricing.validateQuote(quote, userId, String.valueOf(targetResourceId), String.valueOf(targetSlotId), target.partySize(), bookingId, request.expectedVersion(), current);
            long oldSlotId = number(booking, "slot_id");
            int oldUnits = integer(booking, "units");
            if (targetSlotId == oldSlotId) adjust(targetSlotId, nextUnits - oldUnits);
            else { take(targetSlotId, nextUnits); release(oldSlotId, oldUnits); }
            long schedule = number(booking, "schedule_version") + 1;
            changed(jdbc.update("""
                    UPDATE commerce_booking SET resource_id=?,slot_id=?,resource_name=?,resource_kind=?,party_size=?,units=?,
                    start_at=?,end_at=?,version=version+1,schedule_version=?,checkin_token_hash=?,price_snapshot=? WHERE id=?
                    """, targetResourceId, targetSlotId, text(targetResource, "name"), text(targetResource, "kind"),
                    target.partySize(), nextUnits, targetSlot.get("start_at"), targetSlot.get("end_at"), schedule,
                    digest(token(bookingId, schedule)), quote == null ? onSite() : write(pricing.snapshot(quote)), bookingId));
            if (quote != null) pricing.applyQuote(quote, bookingId, current);
            else pricing.releaseCoupons(bookingId, current);
            cancelReminders(bookingId);
            Map<String, Object> updated = row(bookingId, false);
            reminder(updated, current);
            event(updated, "rescheduled", userId);
            return result(updated, userId, false, "BOOKING_RESCHEDULED", 200, "改期成功，核销凭证已更新");
        });
    }

    public MutationResult checkIn(long actorId, boolean platformAdmin, String bookingId, String key, ActionRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) invalid("请提供用户出示的核销凭证");
        return change(actorId, true, platformAdmin, bookingId, key, "CHECK_IN", request, () -> {
            Map<String, Object> booking = row(bookingId, true);
            resource(number(booking, "resource_id"), true);
            slot(number(booking, "slot_id"), true);
            pricing.lockCoupons(bookingId, null);
            Instant current = now();
            version(booking, request.expectedVersion());
            requireState(booking, "CONFIRMED");
            Instant start = instant(booking.get("start_at")).minusSeconds(policy.checkinEarlyMinutes() * 60L);
            if (current.isBefore(start) || !current.isBefore(instant(booking.get("end_at")))) {
                fail("BOOKING_CUTOFF_PASSED", "当前不在该预约的核销时间窗口内", 409);
            }
            if (!MessageDigest.isEqual(digest(request.token()).getBytes(StandardCharsets.UTF_8), text(booking, "checkin_token_hash").getBytes(StandardCharsets.UTF_8))) {
                fail("BOOKING_TOKEN_INVALID", "核销凭证无效或已因改期失效，请用户打开当前预约", 409);
            }
            changed(jdbc.update("UPDATE commerce_booking SET status='USED',version=version+1,used_at=UTC_TIMESTAMP(3),checkin_token_hash=NULL WHERE id=? AND status='CONFIRMED'", bookingId));
            pricing.consumeCoupons(bookingId);
            cancelReminders(bookingId);
            Map<String, Object> updated = row(bookingId, false);
            event(updated, "used", actorId);
            return result(updated, actorId, true, "BOOKING_USED", 200, "预约已核销；消费金额按预约约定线下结算");
        });
    }

    public BookingView booking(String bookingId, long userId) {
        Map<String, Object> booking = own(bookingId, userId);
        return view(booking, userId, false, now());
    }

    public BookingView merchantBooking(String bookingId, long actorId, boolean platformAdmin) {
        Map<String, Object> booking = row(bookingId, false);
        authority(number(booking, "shop_id"), actorId, platformAdmin);
        return view(booking, actorId, true, now());
    }

    public List<BookingView> bookings(long userId, int page) {
        positive(userId, "userId");
        if (page < 1 || page > 10000) invalid("页码必须在 1 到 10000 之间");
        Instant current = now();
        return jdbc.queryForList("SELECT * FROM commerce_booking WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?", userId, (page - 1) * 20)
                .stream().map(row -> view(row, userId, false, current)).toList();
    }

    public List<BookingView> merchantBookings(long shopId, long actorId, boolean platformAdmin, Instant from, Instant to) {
        authority(shopId, actorId, platformAdmin);
        Instant begin = from == null ? now().minus(Duration.ofDays(1)) : from;
        Instant end = to == null ? begin.plus(Duration.ofDays(policy.publishingDays())) : to;
        window(begin, end);
        Instant current = now();
        return jdbc.queryForList("SELECT * FROM commerce_booking WHERE shop_id=? AND start_at>=? AND start_at<? ORDER BY start_at,id LIMIT 500",
                shopId, time(begin), time(end)).stream().map(row -> view(row, actorId, true, current)).toList();
    }

    public CommandView commandResult(long actorId, String key) {
        positive(actorId, "actorId");
        requiredKey(key);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM commerce_booking_command WHERE actor_id=? AND idempotency_key=?", actorId, key);
        if (rows.isEmpty()) notFound("该操作尚未提交；结果未知时请使用原幂等键重试");
        Map<String, Object> row = rows.get(0);
        return new CommandView(key, text(row, "action"), text(row, "booking_id"), read(text(row, "result_json"), MutationResult.class), instant(row.get("created_at")));
    }

    public List<NoticeView> notices(long userId) {
        positive(userId, "userId");
        return jdbc.queryForList("""
                SELECT n.* FROM commerce_booking_reminder n JOIN commerce_booking b ON b.id=n.booking_id
                WHERE b.user_id=? AND n.status='VISIBLE' AND n.schedule_version=b.schedule_version
                  AND ((n.kind='REMINDER' AND b.status='CONFIRMED' AND b.end_at>UTC_TIMESTAMP(3))
                       OR (n.kind='MERCHANT_CANCELLED' AND b.status='CANCELLED'))
                ORDER BY n.due_at DESC,n.id DESC LIMIT 100
                """, userId).stream().map(this::noticeView).toList();
    }

    public NoticeView readNotice(long userId, long noticeId) {
        positive(userId, "userId");
        Map<String, Object> located = single("SELECT n.* FROM commerce_booking_reminder n JOIN commerce_booking b ON b.id=n.booking_id WHERE n.id=? AND b.user_id=?", noticeId, userId);
        return transactions.execute(tx -> {
            guard(userId);
            Map<String, Object> booking = row(text(located, "booking_id"), true);
            Map<String, Object> notice = single("SELECT * FROM commerce_booking_reminder WHERE id=? FOR UPDATE", noticeId);
            boolean current = "VISIBLE".equals(text(notice, "status"))
                    && number(notice, "schedule_version") == number(booking, "schedule_version")
                    && ("MERCHANT_CANCELLED".equals(text(notice, "kind")) && "CANCELLED".equals(text(booking, "status"))
                    || "REMINDER".equals(text(notice, "kind")) && "CONFIRMED".equals(text(booking, "status")) && now().isBefore(instant(booking.get("end_at"))));
            if (!current) notFound("提醒不存在或已失效");
            if (notice.get("read_at") == null) changed(jdbc.update("UPDATE commerce_booking_reminder SET read_at=UTC_TIMESTAMP(3) WHERE id=? AND read_at IS NULL", noticeId));
            return noticeView(single("SELECT * FROM commerce_booking_reminder WHERE id=?", noticeId));
        });
    }

    public void expireDue(java.util.function.Consumer<Runnable> execute) {
        List<String> ids = jdbc.queryForList("""
                SELECT id FROM commerce_booking WHERE status='HELD' AND hold_expires_at<=UTC_TIMESTAMP(3)
                UNION ALL
                SELECT id FROM commerce_booking WHERE status='CONFIRMED' AND end_at<=UTC_TIMESTAMP(3)
                LIMIT 100
                """, String.class);
        for (String bookingId : ids) execute.accept(() -> expireOne(bookingId));
    }

    public void expireOne(String bookingId) {
        Map<String, Object> located = row(bookingId, false);
        transactions.executeWithoutResult(tx -> {
            guard(number(located, "user_id"));
            Map<String, Object> booking = row(bookingId, true);
            resource(number(booking, "resource_id"), true);
            slot(number(booking, "slot_id"), true);
            pricing.lockCoupons(bookingId, null);
            Instant current = now();
            if ("HELD".equals(text(booking, "status")) && !current.isBefore(instant(booking.get("hold_expires_at")))) {
                terminal(booking, "HOLD_EXPIRED", "BOOKING_HOLD_EXPIRED", 0, true);
            } else if ("CONFIRMED".equals(text(booking, "status")) && !current.isBefore(instant(booking.get("end_at")))) {
                terminal(booking, "NO_SHOW", "BOOKING_NO_SHOW", 0, false);
            }
        });
    }

    public void deliverDueReminders(java.util.function.Consumer<Runnable> execute) {
        List<String> ids = jdbc.queryForList("SELECT DISTINCT booking_id FROM commerce_booking_reminder WHERE status='PENDING' AND due_at<=UTC_TIMESTAMP(3) LIMIT 100", String.class);
        for (String bookingId : ids) {
            Map<String, Object> located = row(bookingId, false);
            execute.accept(() -> transactions.executeWithoutResult(tx -> {
                guard(number(located, "user_id"));
                Map<String, Object> booking = row(bookingId, true);
                Instant current = now();
                boolean visible = "CONFIRMED".equals(text(booking, "status")) && current.isBefore(instant(booking.get("end_at")));
                jdbc.update("""
                        UPDATE commerce_booking_reminder SET status=CASE WHEN schedule_version=? AND ? THEN 'VISIBLE' ELSE 'CANCELLED' END,
                        delivered_at=CASE WHEN schedule_version=? AND ? THEN UTC_TIMESTAMP(3) ELSE NULL END
                        WHERE booking_id=? AND status='PENDING' AND due_at<=UTC_TIMESTAMP(3)
                        """, number(booking, "schedule_version"), visible, number(booking, "schedule_version"), visible, bookingId);
            }));
        }
    }

    private MutationResult change(long actorId, boolean merchant, boolean platformAdmin, String bookingId,
                                  String key, String action, ActionRequest request, Supplier<MutationResult> work) {
        positive(actorId, "actorId");
        if (request == null) invalid("缺少操作请求");
        requireVersion(request.expectedVersion());
        bounded(request.reason(), 200, "reason");
        bounded(request.token(), 256, "token");
        Map<String, Object> located = row(bookingId, false);
        if (merchant) authority(number(located, "shop_id"), actorId, platformAdmin);
        else if (number(located, "user_id") != actorId) notFound("预约不存在或不属于当前用户");
        return command(actorId, number(located, "user_id"), key, action, requestHash(action + ":" + bookingId, request), work);
    }

    private MutationResult command(long actorId, long ownerId, String key, String action, String hash, Supplier<MutationResult> work) {
        requiredKey(key);
        MutationResult existing = replay(actorId, key, hash);
        if (existing != null) return existing;
        try {
            return transactions.execute(tx -> {
                guard(ownerId);
                MutationResult repeat = replay(actorId, key, hash);
                if (repeat != null) return repeat;
                MutationResult result = work.get();
                changed(jdbc.update("INSERT INTO commerce_booking_command(actor_id,idempotency_key,action,request_hash,booking_id,result_json) VALUES (?,?,?,?,?,?)",
                        actorId, key, action, hash, result.booking().id(), write(result)));
                return result;
            });
        } catch (DuplicateKeyException collision) {
            // The transaction has rolled back before observing the winner's committed command.
            MutationResult winner = replay(actorId, key, hash);
            if (winner != null) return winner;
            throw collision;
        }
    }

    private MutationResult replay(long actorId, String key, String hash) {
        List<Map<String, Object>> records = jdbc.queryForList("SELECT request_hash,result_json FROM commerce_booking_command WHERE actor_id=? AND idempotency_key=?", actorId, key);
        if (records.isEmpty()) return null;
        if (!hash.equals(text(records.get(0), "request_hash"))) fail("BOOKING_IDEMPOTENCY_CONFLICT", "该幂等键已绑定其他操作，请勿更换参数重试", 409);
        return read(text(records.get(0), "result_json"), MutationResult.class);
    }

    private void guard(long userId) {
        // Duplicate-key UPDATE acquires X directly; INSERT IGNORE followed by FOR UPDATE can deadlock upgrading S locks.
        jdbc.update("INSERT INTO commerce_booking_user_guard(user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id=user_id", userId);
    }

    private void quota(long userId, Instant current) {
        Long held = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_booking WHERE user_id=? AND status='HELD' AND hold_expires_at>?", Long.class, userId, time(current));
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_booking WHERE user_id=? AND ((status='HELD' AND hold_expires_at>?) OR (status='CONFIRMED' AND start_at>?))",
                Long.class, userId, time(current), time(current));
        if (held != null && held >= policy.maxActiveHolds() || active != null && active >= policy.maxFutureBookings()) {
            fail("BOOKING_QUOTA_EXCEEDED", "有效暂留或未来预约已达上限，请先处理已有预约", 409);
        }
    }

    private void terminal(Map<String, Object> booking, String state, String reason, long actorId, boolean release) {
        String bookingId = text(booking, "id");
        changed(jdbc.update("UPDATE commerce_booking SET status=?,reason_code=?,version=version+1,checkin_token_hash=NULL WHERE id=? AND status=?",
                state, reason, bookingId, text(booking, "status")));
        if (release) release(number(booking, "slot_id"), integer(booking, "units"));
        pricing.releaseCoupons(bookingId, now());
        cancelReminders(bookingId);
        event(row(bookingId, false), state.toLowerCase(Locale.ROOT), actorId);
    }

    private void take(long slotId, int units) {
        int updated = jdbc.update("UPDATE commerce_booking_slot SET occupied=occupied+?,version=version+1 WHERE id=? AND status='OPEN' AND occupied+?<=capacity", units, slotId, units);
        if (updated != 1) fail("BOOKING_SLOT_FULL", "该时段剩余名额不足，请选择其他时段", 409);
    }

    private Target target(String resourceId, String slotId, int partySize, String quoteId, long userId) {
        if (quoteId != null) {
            QuoteView quote = pricing.getQuote(quoteId, userId);
            if (resourceId != null && !resourceId.equals(quote.resourceId()) || slotId != null && !slotId.equals(quote.slotId())
                    || partySize > 0 && partySize != quote.partySize()) {
                fail("BOOKING_QUOTE_CHANGED", "报价与预约目标不一致，请重新选择方案", 409);
            }
            resourceId = quote.resourceId();
            slotId = quote.slotId();
            partySize = quote.partySize();
        }
        if (partySize < 1) invalid("预约人数必须大于零");
        return new Target(id(resourceId, "resourceId"), id(slotId, "slotId"), partySize);
    }

    private QuoteView lockQuote(String quoteId, long userId, long resourceId) {
        if (quoteId != null) return pricing.lockQuoteMetadata(quoteId, userId);
        pricing.requireOnSiteAllowed(String.valueOf(resourceId));
        return null;
    }

    private record Target(long resourceId, long slotId, int partySize) {}

    private void release(long slotId, int units) {
        changed(jdbc.update("UPDATE commerce_booking_slot SET occupied=occupied-?,version=version+1 WHERE id=? AND occupied>=?", units, slotId, units));
    }

    private void adjust(long slotId, int delta) {
        if (delta > 0) take(slotId, delta);
        else if (delta < 0) release(slotId, -delta);
    }

    private Map<Long, Map<String, Object>> lockResources(long... ids) {
        Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        Arrays.stream(ids).distinct().sorted().forEach(id -> rows.put(id, resource(id, true)));
        return rows;
    }

    private Map<Long, Map<String, Object>> lockSlots(long... ids) {
        Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        Arrays.stream(ids).distinct().sorted().forEach(id -> rows.put(id, slot(id, true)));
        return rows;
    }

    private Map<String, Object> own(String bookingId, long userId) {
        positive(userId, "userId");
        Map<String, Object> result = row(bookingId, false);
        if (number(result, "user_id") != userId) notFound("预约不存在或不属于当前用户");
        return result;
    }

    private Map<String, Object> row(String bookingId, boolean lock) {
        if (bookingId == null || !bookingId.matches("[0-9a-fA-F-]{36}")) invalid("预约编号格式不正确");
        return single("SELECT * FROM commerce_booking WHERE id=?" + (lock ? " FOR UPDATE" : ""), bookingId);
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

    private void available(Map<String, Object> resource, Map<String, Object> slot, long resourceId, Instant current) {
        if (number(slot, "resource_id") != resourceId) invalid("时段与资源不匹配");
        if (!"OPEN".equals(text(resource, "status")) || !"OPEN".equals(text(slot, "status"))) {
            fail("BOOKING_RESOURCE_UNAVAILABLE", "该资源或时段已停止接收新预约", 409);
        }
        if (!current.isBefore(instant(slot.get("start_at")))) fail("BOOKING_CUTOFF_PASSED", "时段已开始，请选择未来时段", 409);
    }

    private int units(Map<String, Object> resource, int partySize) {
        int limit = integer(resource, "max_party_size");
        if ("DINING_POOL".equals(text(resource, "kind"))) limit = Math.min(limit, policy.maxDiningPartySize());
        if (partySize < 1 || partySize > limit) invalid("预约人数超出该资源允许范围（1～" + limit + "）");
        return "COURT".equals(text(resource, "kind")) ? 1 : partySize;
    }

    private void cutoff(Map<String, Object> booking, Instant current) {
        if (!current.isBefore(instant(booking.get("start_at")).minusSeconds(policy.cancellationMinutes() * 60L))) {
            fail("BOOKING_CUTOFF_PASSED", "已超过原预约开始前 " + policy.cancellationMinutes() + " 分钟的取消或改期截止时间", 409);
        }
    }

    private void reminder(Map<String, Object> booking, Instant current) {
        Instant due = instant(booking.get("start_at")).minusSeconds(policy.reminderMinutes() * 60L);
        if (!due.isBefore(current)) notice(booking, "REMINDER", "预约即将开始，请查看当前预约时间并按时到店", due, false);
    }

    private void notice(Map<String, Object> booking, String kind, String message, Instant due, boolean visible) {
        changed(jdbc.update("INSERT INTO commerce_booking_reminder(booking_id,schedule_version,kind,message,due_at,status,delivered_at) VALUES (?,?,?,?,?,?,?)",
                text(booking, "id"), number(booking, "schedule_version"), kind, message, time(due), visible ? "VISIBLE" : "PENDING", visible ? time(due) : null));
    }

    private void cancelReminders(String bookingId) {
        jdbc.update("UPDATE commerce_booking_reminder SET status='CANCELLED' WHERE booking_id=? AND status IN ('PENDING','VISIBLE')", bookingId);
    }

    private void event(Map<String, Object> booking, String action, long actorId) {
        String eventId = UUID.randomUUID().toString();
        BookingChangedEvent payload = new BookingChangedEvent(text(booking, "id"), text(booking, "shop_id"),
                text(booking, "user_id"), text(booking, "resource_id"), text(booking, "slot_id"), integer(booking, "party_size"),
                integer(booking, "units"), text(booking, "status"), number(booking, "schedule_version"),
                instant(booking.get("start_at")), instant(booking.get("end_at")), snapshot(booking), nullableText(booking, "source"),
                nullableText(booking, "request_id"), actorId);
        EventEnvelope<BookingChangedEvent> event = new EventEnvelope<>(
                eventId, "commerce.booking." + action, BookingChangedEvent.SCHEMA_VERSION,
                "commerce_booking", text(booking, "id"), number(booking, "version"), "commerce-service",
                now().toString(), text(booking, "id"), payload);
        changed(jdbc.update("INSERT INTO commerce_outbox_event(event_id,event_type,aggregate_type,aggregate_id,aggregate_version,topic,payload_json,status) VALUES (?,?,'commerce_booking',?,?,'citycopilot-commerce-events-v1',?,'READY')",
                eventId, event.eventType(), text(booking, "id"), number(booking, "version"), write(event)));
    }

    private MutationResult result(Map<String, Object> booking, long actorId, boolean merchant, String code, int status, String message) {
        return new MutationResult(code, status, message, view(booking, actorId, merchant, now()));
    }

    private BookingView view(Map<String, Object> booking, long actorId, boolean merchant, Instant current) {
        String state = text(booking, "status");
        List<String> actions = new ArrayList<>();
        String checkinToken = null;
        Instant start = instant(booking.get("start_at"));
        Instant end = instant(booking.get("end_at"));
        if ("HELD".equals(state) && current.isBefore(instant(booking.get("hold_expires_at")))) {
            if (!merchant) actions.add("CONFIRM");
            actions.add("CANCEL");
        }
        if ("CONFIRMED".equals(state)) {
            if (merchant) {
                if (current.isBefore(start)) actions.add("CANCEL");
                if (!current.isBefore(start.minusSeconds(policy.checkinEarlyMinutes() * 60L)) && current.isBefore(end)) actions.add("CHECK_IN");
            } else if (current.isBefore(start.minusSeconds(policy.cancellationMinutes() * 60L))) {
                actions.add("CANCEL");
                actions.add("RESCHEDULE");
            }
            if (!merchant && number(booking, "user_id") == actorId && current.isBefore(end)) {
                String candidate = token(text(booking, "id"), number(booking, "schedule_version"));
                if (digest(candidate).equals(text(booking, "checkin_token_hash"))) checkinToken = candidate;
            }
        }
        return new BookingView(text(booking, "id"), text(booking, "user_id"), text(booking, "shop_id"), text(booking, "resource_id"),
                text(booking, "resource_name"), text(booking, "resource_kind"), text(booking, "slot_id"), integer(booking, "party_size"),
                integer(booking, "units"), state, nullableText(booking, "reason_code"), start, end, instant(booking.get("hold_expires_at")),
                number(booking, "version"), number(booking, "schedule_version"), snapshot(booking), checkinToken, List.copyOf(actions), current);
    }

    private NoticeView noticeView(Map<String, Object> row) {
        return new NoticeView(text(row, "id"), text(row, "booking_id"), number(row, "schedule_version"), text(row, "kind"), text(row, "message"), instant(row.get("due_at")), instant(row.get("delivered_at")), instant(row.get("read_at")));
    }

    private String token(String bookingId, long scheduleVersion) {
        if (policy.checkinSecret() == null || policy.checkinSecret().isBlank()) fail("BOOKING_CREDENTIAL_UNAVAILABLE", "预约核销密钥未配置，请联系运营", 503);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(policy.checkinSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("booking-checkin:v1:" + bookingId + ":" + scheduleVersion).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException("Unable to generate booking credential", error); }
    }

    private Map<String, Object> snapshot(Map<String, Object> booking) {
        try { return json.readValue(text(booking, "price_snapshot"), new TypeReference<>() {}); }
        catch (Exception error) { throw new IllegalStateException("Invalid persisted booking price snapshot", error); }
    }

    private String onSite() { return "{\"pricingMode\":\"ON_SITE\",\"currency\":\"CNY\",\"totalCents\":null}"; }
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
    private static String nullableText(Map<String, Object> row, String key) { return row.get(key) == null ? null : text(row, key); }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Unable to serialize booking result", error); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception error) { throw new IllegalStateException("Invalid persisted booking result", error); }
    }
    private String requestHash(String action, Object request) { return digest(action + "\n" + write(request)); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void authority(long shopId, long actorId, boolean platformAdmin) { positive(actorId, "actorId"); positive(shopId, "shopId"); place.requireWriteAuthority(shopId, actorId, platformAdmin); }
    private void requireNewBookings() { if (!policy.newBookingsEnabled()) fail("BOOKING_NEW_DISABLED", "暂不接受新预约；已有预约仍可查询和处理", 503); }
    private void window(Instant from, Instant to) { if (!to.isAfter(from) || to.isAfter(from.plus(Duration.ofDays(policy.publishingDays())))) invalid("查询时间窗口必须为正且不超过 " + policy.publishingDays() + " 天"); }
    private static void version(Map<String, Object> row, Long expected) { if (expected == null || number(row, "version") != expected) fail("BOOKING_VERSION_CONFLICT", "预约或资源已变化，请刷新后确认当前内容", 409); }
    private static void requireVersion(Long value) { if (value == null || value < 0) invalid("expectedVersion 必须是非负版本号"); }
    private static void requireState(Map<String, Object> booking, String expected) { if (!expected.equals(text(booking, "status"))) conflict("预约当前状态不允许该操作"); }
    private static long id(String value, String name) { try { long id = Long.parseLong(value); positive(id, name); return id; } catch (NumberFormatException | NullPointerException invalid) { invalid(name + " 必须为正整数 ID"); return 0; } }
    private static void positive(long value, String field) { if (value <= 0) invalid(field + " 必须为正整数"); }
    private static void requiredKey(String value) { if (value == null || value.isBlank() || value.length() > 128) invalid("Idempotency-Key 必须为 1～128 个字符"); }
    private static void bounded(String value, int max, String field) { if (value != null && value.length() > max) invalid(field + " 长度超过 " + max); }
    private static void changed(int affected) { if (affected != 1) throw new IllegalStateException("Booking mutation did not affect exactly one row"); }
    private static void conflict(String message) { fail("BOOKING_STATE_CONFLICT", message, 409); }
    private static void notFound(String message) { fail("BOOKING_NOT_FOUND", message, 404); }
    private static void invalid(String message) { fail("BOOKING_INVALID_ARGUMENT", message, 400); }
    private static void fail(String code, String message, int status) { throw new CommerceApiException(code, message, status); }
}
