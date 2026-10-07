package com.citycopilot.commerce.pricing;

import com.citycopilot.commerce.CommerceApiException;
import com.citycopilot.commerce.PlaceAuthorityClient;
import com.citycopilot.contract.booking.BookingContracts.ResourceView;
import com.citycopilot.contract.booking.BookingContracts.SlotView;
import com.citycopilot.contract.booking.PricingContracts.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

import static com.citycopilot.commerce.pricing.BookingPriceCalculator.*;

/** 价目、预约券与报价；交易 hooks 加入调用方已有事务，不另开事务。 */
@Service
public class BookingPricingService {
    private static final String COUPON_QUERY = """
            SELECT r.*,c.id AS coupon_id,c.user_id AS coupon_user_id,c.status AS coupon_status,
                   c.expires_at AS coupon_expiry,c.booking_id AS coupon_booking_id,c.revoked_at AS coupon_revoked_at
            FROM commerce_user_booking_coupon c JOIN commerce_booking_coupon_rule r ON r.id=c.rule_id
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final PlaceAuthorityClient places;
    private final int quoteSeconds;
    private final boolean newBookingsEnabled;

    public BookingPricingService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                 ObjectMapper json, PlaceAuthorityClient places) {
        this(jdbc, transactions, json, places, 300, true);
    }

    public BookingPricingService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                 ObjectMapper json, PlaceAuthorityClient places, int quoteSeconds) {
        this(jdbc, transactions, json, places, quoteSeconds, true);
    }

    @Autowired
    public BookingPricingService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                 ObjectMapper json, PlaceAuthorityClient places,
                                 @Value("${citycopilot.booking.quote-seconds:300}") int quoteSeconds,
                                 @Value("${citycopilot.booking.new-bookings-enabled:true}") boolean newBookingsEnabled) {
        if (quoteSeconds < 1) throw new IllegalArgumentException("Quote lifetime must be positive");
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json.rebuild().findAndAddModules().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        this.places = places;
        this.quoteSeconds = quoteSeconds;
        this.newBookingsEnabled = newBookingsEnabled;
    }

    public List<OfferView> offers(String resourceId, boolean includeStopped) {
        return jdbc.query("SELECT * FROM commerce_booking_offer WHERE resource_id=?"
                        + (includeStopped ? "" : " AND status='OPEN'") + " ORDER BY id",
                this::offerRow, id(resourceId));
    }

    public List<OfferView> managedOffers(String resourceId, long actorId, boolean admin) {
        places.requireWriteAuthority(resource(resourceId, false).shopId(), actorId, admin);
        return offers(resourceId, true);
    }

    public OfferView saveOffer(String resourceId, OfferRequest request, long actorId, boolean admin) {
        if (request == null) throw invalid("价目请求不能为空");
        Resource resource = resource(resourceId, false);
        places.requireWriteAuthority(resource.shopId(), actorId, admin);
        String kind = resource.kind().equals("COURT") ? "COURT_SLOT" : "PER_PERSON_PACKAGE";
        if (!kind.equals(request.kind())) throw invalid("价目类型必须与场馆或餐厅资源对应");
        text(request.title(), 160, "价目名称");
        optionalText(request.includedItems(), 1000, "包含项目");
        optionalText(request.excludedItems(), 1000, "另收费项目");
        amount(request.unitPriceCents());
        String status = status(request.status());
        return transactions.execute(tx -> {
            resource(resourceId, true);
            if (kind.equals("COURT_SLOT") && status.equals("OPEN")) {
                Long count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM commerce_booking_offer
                        WHERE resource_id=? AND status='OPEN' AND id<>?
                        """, Long.class, id(resourceId), request.offerId() == null ? -1 : id(request.offerId()));
                if (count != null && count > 0) throw conflict("BOOKING_OFFER_CONFLICT", "该场地已有生效价目，请更新原价目");
            }
            long offerId;
            if (request.offerId() == null) {
                GeneratedKeyHolder key = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO commerce_booking_offer
                            (resource_id,shop_id,kind,title,included_items,excluded_items,unit_price_cents,status)
                            VALUES (?,?,?,?,?,?,?,?)
                            """, Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, resource.id());
                    statement.setLong(2, resource.shopId());
                    statement.setString(3, kind);
                    statement.setString(4, request.title());
                    statement.setString(5, blank(request.includedItems()));
                    statement.setString(6, blank(request.excludedItems()));
                    statement.setLong(7, request.unitPriceCents());
                    statement.setString(8, status);
                    return statement;
                }, key);
                offerId = Objects.requireNonNull(key.getKey()).longValue();
            } else {
                offerId = id(request.offerId());
                OfferView existing = offer(offerId, true);
                if (!existing.resourceId().equals(resourceId)) throw invalid("价目不属于该预约资源");
                if (request.expectedVersion() == null || existing.version() != request.expectedVersion()) {
                    throw conflict("BOOKING_VERSION_CONFLICT", "价目已更新，请刷新后重试");
                }
                changed(jdbc.update("""
                        UPDATE commerce_booking_offer
                        SET title=?,included_items=?,excluded_items=?,unit_price_cents=?,status=?,version=version+1
                        WHERE id=? AND version=?
                        """, request.title(), blank(request.includedItems()), blank(request.excludedItems()),
                        request.unitPriceCents(), status, offerId, request.expectedVersion()));
            }
            return offer(offerId, false);
        });
    }

    public List<RuleView> rules(String shopId, boolean includeStopped) {
        return jdbc.query("SELECT * FROM commerce_booking_coupon_rule WHERE shop_id=?"
                        + (includeStopped ? "" : " AND issuance_status='OPEN' AND claim_until>UTC_TIMESTAMP(3)")
                        + " ORDER BY id DESC", this::ruleRow, id(shopId));
    }

    public List<RuleView> managedRules(String shopId, long actorId, boolean admin) {
        places.requireWriteAuthority(id(shopId), actorId, admin);
        return rules(shopId, true);
    }

    public RuleView publishRule(String shopId, RuleRequest request, long actorId, boolean admin) {
        places.requireWriteAuthority(id(shopId), actorId, admin);
        validateRule(request);
        String campaign = request.campaignKey() == null ? UUID.randomUUID().toString() : request.campaignKey();
        text(campaign, 64, "活动标识");
        if (request.offerId() != null && !offer(id(request.offerId()), false).shopId().equals(shopId)) {
            throw invalid("券适用价目不属于当前店铺");
        }
        return transactions.execute(tx -> {
            List<RuleView> previous = jdbc.query("""
                    SELECT * FROM commerce_booking_coupon_rule WHERE campaign_key=?
                    ORDER BY rule_version DESC LIMIT 1 FOR UPDATE
                    """, this::ruleRow, campaign);
            if (!previous.isEmpty() && !previous.get(0).shopId().equals(shopId)) {
                throw forbidden("不能为其他店铺的活动发布新版本");
            }
            long version = previous.isEmpty() ? 1 : Math.addExact(previous.get(0).ruleVersion(), 1);
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO commerce_booking_coupon_rule
                        (campaign_key,rule_version,shop_id,offer_id,type,threshold_cents,reduction_cents,
                         pay_factor_bps,discount_cap_cents,stackable,service_from,service_to,claim_until,coupon_expires_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, campaign);
                statement.setLong(2, version);
                statement.setLong(3, id(shopId));
                statement.setObject(4, request.offerId() == null ? null : id(request.offerId()));
                statement.setString(5, request.type());
                statement.setLong(6, request.thresholdCents());
                statement.setLong(7, request.reductionCents());
                statement.setInt(8, request.payFactorBps());
                statement.setObject(9, request.discountCapCents());
                statement.setBoolean(10, request.stackable());
                statement.setObject(11, utc(request.serviceFrom()));
                statement.setObject(12, utc(request.serviceTo()));
                statement.setObject(13, utc(request.claimUntil()));
                statement.setObject(14, utc(request.couponExpiresAt()));
                return statement;
            }, key);
            return rule(Objects.requireNonNull(key.getKey()).longValue(), false);
        });
    }

    public RuleView setIssuance(String ruleId, String newStatus, long actorId, boolean admin) {
        RuleView rule = rule(id(ruleId), false);
        places.requireWriteAuthority(id(rule.shopId()), actorId, admin);
        changed(jdbc.update("UPDATE commerce_booking_coupon_rule SET issuance_status=? WHERE id=?",
                status(newStatus), id(ruleId)));
        return rule(id(ruleId), false);
    }

    public List<CouponView> coupons(long userId, String shopId) {
        user(userId);
        return shopId == null
                ? jdbc.query(COUPON_QUERY + " WHERE c.user_id=? ORDER BY c.created_at DESC LIMIT 100", this::joinedCouponRow, userId)
                : jdbc.query(COUPON_QUERY + " WHERE c.user_id=? AND r.shop_id=? ORDER BY c.created_at DESC LIMIT 100",
                        this::joinedCouponRow, userId, id(shopId));
    }

    public CouponView claim(long userId, String ruleId, String idempotencyKey) {
        user(userId);
        text(idempotencyKey, 128, "幂等键");
        long selectedRule = id(ruleId);
        return transactions.execute(tx -> {
            lockUser(userId);
            List<String> replay = jdbc.queryForList("SELECT id FROM commerce_user_booking_coupon WHERE user_id=? AND claim_key=?",
                    String.class, userId, idempotencyKey);
            if (!replay.isEmpty()) {
                CouponView previous = coupon(replay.get(0), false);
                if (!previous.rule().id().equals(ruleId)) throw conflict("BOOKING_IDEMPOTENCY_CONFLICT", "同一领取幂等键不能改领其他券");
                return previous;
            }
            RuleView rule = rule(selectedRule, true);
            Instant now = databaseNow();
            if (!rule.issuanceStatus().equals("OPEN") || !rule.claimUntil().isAfter(now) || !rule.couponExpiresAt().isAfter(now)) {
                throw unavailable("当前预约券已停止领取或已过期");
            }
            Long claimed = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_user_booking_coupon WHERE user_id=? AND rule_id=?",
                    Long.class, userId, selectedRule);
            if (claimed != null && claimed > 0) throw conflict("BOOKING_COUPON_ALREADY_CLAIMED", "该版本预约券已领取，请查看我的优惠券");
            jdbc.update("""
                    UPDATE commerce_user_booking_coupon SET status=CASE WHEN revoked_at IS NOT NULL THEN 'REVOKED' ELSE 'EXPIRED' END
                    WHERE user_id=? AND status='AVAILABLE' AND (expires_at<=? OR revoked_at IS NOT NULL)
                    """, userId, utc(now));
            Long active = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM commerce_user_booking_coupon c JOIN commerce_booking_coupon_rule r ON r.id=c.rule_id
                    WHERE c.user_id=? AND r.shop_id=? AND c.status IN ('AVAILABLE','RESERVED')
                    """, Long.class, userId, id(rule.shopId()));
            if (active != null && active >= MAX_CANDIDATES) throw conflict("BOOKING_COUPON_LIMIT", "同店可用与已锁定预约券合计已达20张");
            String couponId = UUID.randomUUID().toString();
            changed(jdbc.update("""
                    INSERT INTO commerce_user_booking_coupon(id,user_id,rule_id,expires_at,claim_key)
                    VALUES (?,?,?,?,?)
                    """, couponId, userId, selectedRule, utc(rule.couponExpiresAt()), idempotencyKey));
            return coupon(couponId, false);
        });
    }

    public CouponView revoke(String couponId, String reason, long actorId, boolean admin) {
        text(reason, 255, "撤券原因");
        CouponView before = coupon(couponId, false);
        places.requireWriteAuthority(id(before.rule().shopId()), actorId, admin);
        return transactions.execute(tx -> {
            lockUser(id(before.userId()));
            CouponView current = coupon(couponId, true);
            if (current.status().equals("CONSUMED")) throw conflict("BOOKING_COUPON_CONSUMED", "已核销券不能撤销");
            // 已锁券只登记资格撤销；已确认预约的价格承诺由预约状态机处理。
            changed(jdbc.update("""
                    UPDATE commerce_user_booking_coupon
                    SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)),revoked_reason=?,
                        status=CASE WHEN status='RESERVED' THEN status ELSE 'REVOKED' END
                    WHERE id=?
                    """, reason, couponId));
            return coupon(couponId, false);
        });
    }

    /** 试算只持久化报价依据，不锁券、不占名额。 */
    public QuoteView quote(long userId, QuoteRequest request) {
        user(userId);
        if (request == null) throw invalid("报价请求不能为空");
        return transactions.execute(tx -> createQuote(userId, request));
    }

    private QuoteView createQuote(long userId, QuoteRequest request) {
        String purpose = request.purpose() == null ? "CREATE" : request.purpose();
        if (!List.of("CREATE", "RESCHEDULE").contains(purpose)) throw invalid("报价用途无效");
        if (purpose.equals("CREATE") && !newBookingsEnabled) {
            throw new CommerceApiException("BOOKING_NEW_DISABLED", "新预约暂时关闭，已有预约仍可查询和处理", 503);
        }
        Resource resource = resource(request.resourceId(), false);
        Slot slot = slot(request.slotId());
        validateTarget(resource, slot, request.partySize());
        Instant now = databaseNow();
        if (!slot.start().isAfter(now)) throw conflict("BOOKING_CUTOFF_PASSED", "该时段已开始，请选择其他时段");
        int units = resource.kind().equals("COURT") ? 1 : request.partySize();
        Map<String, Object> previous = null;
        if (purpose.equals("RESCHEDULE")) {
            previous = bookingForQuote(request.bookingId(), userId, request.expectedBookingVersion());
            if (((Number) previous.get("shop_id")).longValue() != resource.shopId()) throw invalid("只能改到同一店铺");
        } else if (request.bookingId() != null || request.expectedBookingVersion() != null) {
            throw invalid("新预约报价不能绑定已有预约");
        }
        int ownUnits = previous != null && ((Number) previous.get("slot_id")).longValue() == slot.id()
                ? ((Number) previous.get("units")).intValue() : 0;
        if ((long) slot.capacity() - slot.occupied() + ownUnits < units) throw conflict("BOOKING_SLOT_FULL", "该时段剩余名额不足");
        OfferView offer = request.offerId() == null ? null : offer(id(request.offerId()), false);
        if (offer != null && (!offer.resourceId().equals(request.resourceId()) || !offer.status().equals("OPEN"))) {
            throw conflict("BOOKING_QUOTE_CHANGED", "所选价目已停售或不属于该资源，请重新选择");
        }
        if (offer == null) requireOnSiteAllowed(request.resourceId());
        QuoteView quote = buildQuote(userId, request, purpose, resource, slot, offer, now, previous);
        persistQuote(quote);
        return quote;
    }

    private QuoteView buildQuote(long userId, QuoteRequest request, String purpose, Resource resource,
                                  Slot slot, OfferView offer,
                                  Instant now, Map<String, Object> previous) {
        int units = resource.kind().equals("COURT") ? 1 : request.partySize();
        String mode = request.couponMode() == null ? "AUTO" : request.couponMode();
        if (!List.of("AUTO", "NONE", "MANUAL").contains(mode)) throw invalid("选券模式必须是AUTO、NONE或MANUAL");
        List<String> selectedIds = request.couponIds() == null ? List.of() : List.copyOf(request.couponIds());
        if (!mode.equals("MANUAL") && !selectedIds.isEmpty()) throw invalid("只有手动选券模式可提供券标识");
        if (offer == null && !selectedIds.isEmpty()) throw invalid("纯订位没有固定金额，不能使用预约优惠券");
        List<DiscountView> discounts = List.of();
        Long base = null;
        Long total = null;
        if (offer != null) {
            base = checkedBase(offer.unitPriceCents(), resource.kind().equals("COURT") ? 1 : request.partySize());
            String reuseBooking = purpose.equals("RESCHEDULE") ? request.bookingId() : null;
            List<CouponView> candidates = eligibleCoupons(userId, resource.shopId(), offer.id(), slot, reuseBooking, now);
            List<CouponView> chosen = mode.equals("NONE") ? List.of() : mode.equals("MANUAL")
                    ? selected(candidates, selectedIds) : candidates;
            Price price = calculatePrice(base, chosen, mode.equals("AUTO"));
            discounts = discountViews(price, chosen);
            total = price.totalCents();
        }
        String source = previous == null ? request.source() : (String) previous.get("source");
        String requestId = previous == null ? request.requestId() : (String) previous.get("request_id");
        optionalText(source, 32, "来源");
        optionalText(requestId, 128, "请求标识");
        return new QuoteView(UUID.randomUUID().toString(), String.valueOf(userId), purpose,
                request.bookingId(), request.expectedBookingVersion(), request.resourceId(), request.slotId(),
                request.partySize(), units, offer == null ? "ON_SITE" : "FIXED", offer == null ? null : offer.id(),
                offer == null ? null : offer.version(), offer == null ? null : offer.title(),
                offer == null ? null : offer.includedItems(), offer == null ? null : offer.excludedItems(),
                offer == null ? null : offer.unitPriceCents(), "CNY", base, discounts, total,
                now.plusSeconds(quoteSeconds), source, requestId);
    }

    void persistQuote(QuoteView quote) {
        inTransaction();
        changed(jdbc.update("""
                INSERT INTO commerce_booking_quote(id,user_id,resource_id,offer_id,purpose,expires_at,snapshot_json)
                VALUES (?,?,?,?,?,?,?)
                """, quote.quoteId(), id(quote.userId()), id(quote.resourceId()), quote.offerId() == null ? null : id(quote.offerId()),
                quote.purpose(), utc(quote.expiresAt()), encode(quote)));
    }

    public QuoteView getQuote(String quoteId, long userId) {
        QuoteView quote = quoteRow(quoteId, false);
        if (!quote.userId().equals(String.valueOf(userId))) throw forbidden("不能查看其他用户的报价");
        return quote;
    }

    /** 调用方已按 ID 锁好资源；这里继续锁价目，再锁不可变报价。 */
    public QuoteView lockQuoteMetadata(String quoteId, long userId) {
        inTransaction();
        QuoteView before = getQuote(quoteId, userId);
        jdbc.queryForList("SELECT id FROM commerce_booking_offer WHERE resource_id=? ORDER BY id FOR UPDATE", Long.class, id(before.resourceId()));
        QuoteView locked = quoteRow(quoteId, true);
        if (!locked.userId().equals(String.valueOf(userId))) throw forbidden("报价不属于当前用户");
        return locked;
    }

    /** 调用方已锁旧新时段，之后才锁旧新券集合；不可在本方法之后反向锁资源。 */
    public void lockCoupons(String bookingId, QuoteView target) {
        inTransaction();
        TreeSet<String> ids = new TreeSet<>();
        if (bookingId != null) ids.addAll(boundCouponIds(bookingId));
        if (target != null) target.discountLines().forEach(line -> ids.add(line.couponId()));
        ids.forEach(couponId -> coupon(couponId, true));
    }

    public void validateQuote(QuoteView quote, long userId, String resourceId, String slotId, int partySize,
                              String bookingId, Long expectedBookingVersion, Instant now) {
        inTransaction();
        if (!quote.userId().equals(String.valueOf(userId)) || !quote.resourceId().equals(resourceId)
                || !quote.slotId().equals(slotId) || quote.partySize() != partySize
                || !Objects.equals(quote.bookingId(), bookingId)
                || !Objects.equals(quote.expectedBookingVersion(), expectedBookingVersion)
                || !quote.purpose().equals(bookingId == null ? "CREATE" : "RESCHEDULE")) {
            throw conflict("BOOKING_QUOTE_CHANGED", "报价与当前预约目标不一致，请重新报价");
        }
        if (!quote.expiresAt().isAfter(now)) throw conflict("BOOKING_QUOTE_CHANGED", "报价已过期，请重新报价");
        String consumed = jdbc.queryForObject("SELECT consumed_booking_id FROM commerce_booking_quote WHERE id=?", String.class, quote.quoteId());
        if (consumed != null) throw conflict("BOOKING_QUOTE_CONSUMED", "报价已用于预约，请查询原操作结果");
        if (quote.offerId() == null) {
            requireOnSiteAllowed(resourceId);
            return;
        }
        OfferView offer = offer(id(quote.offerId()), false);
        if (!offer.status().equals("OPEN") || offer.version() != quote.offerVersion()) {
            throw conflict("BOOKING_QUOTE_CHANGED", "商家价目已变更，请重新报价并确认差异");
        }
        Slot slot = slot(slotId);
        List<CouponView> chosen = quote.discountLines().stream().map(line -> coupon(line.couponId(), false)).toList();
        for (CouponView coupon : chosen) {
            if (!eligible(coupon, userId, id(offer.shopId()), offer.id(), slot, bookingId, now)) {
                throw unavailable("所选券已过期、被撤销、已被使用或不适用新时段，请重新报价");
            }
        }
        long base = checkedBase(offer.unitPriceCents(), offer.kind().equals("COURT_SLOT") ? 1 : partySize);
        Price recalculated = calculatePrice(base, chosen, false);
        if (!Objects.equals(quote.baseCents(), base) || !Objects.equals(quote.totalCents(), recalculated.totalCents())
                || !quote.discountLines().equals(discountViews(recalculated, chosen))) {
            throw conflict("BOOKING_QUOTE_CHANGED", "价格明细已变更，请重新报价并确认差异");
        }
    }

    /** 必须在新预约插入后调用；名额、券集合和报价消费随调用方事务一起提交。 */
    public void applyQuote(QuoteView quote, String bookingId, Instant now) {
        inTransaction();
        List<String> newIds = quote.discountLines().stream().map(DiscountView::couponId).toList();
        for (String oldId : boundCouponIds(bookingId)) {
            if (!newIds.contains(oldId)) release(oldId, bookingId, now);
        }
        for (String couponId : newIds) {
            changed(jdbc.update("""
                    UPDATE commerce_user_booking_coupon SET status='RESERVED',booking_id=?
                    WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>?
                      AND (status='AVAILABLE' OR (status='RESERVED' AND booking_id=?))
                    """, bookingId, couponId, id(quote.userId()), utc(now), bookingId));
        }
        changed(jdbc.update("UPDATE commerce_booking_quote SET consumed_booking_id=? WHERE id=? AND consumed_booking_id IS NULL",
                bookingId, quote.quoteId()));
    }

    public Map<String, Object> snapshot(QuoteView quote) {
        return json.convertValue(quote, new TypeReference<>() {});
    }

    public Instant earliestCouponExpiry(QuoteView quote) {
        return quote.discountLines().stream().map(DiscountView::couponExpiresAt).min(Instant::compareTo).orElse(null);
    }

    public boolean heldCouponsValid(String bookingId, Instant now) {
        inTransaction();
        Long invalid = jdbc.queryForObject("""
                SELECT COUNT(*) FROM commerce_user_booking_coupon WHERE booking_id=? AND status='RESERVED'
                AND (revoked_at IS NOT NULL OR expires_at<=?)
                """, Long.class, bookingId, utc(now));
        return invalid != null && invalid == 0;
    }

    public void releaseCoupons(String bookingId, Instant now) {
        inTransaction();
        for (String couponId : boundCouponIds(bookingId)) release(couponId, bookingId, now);
    }

    public void consumeCoupons(String bookingId) {
        inTransaction();
        for (String couponId : boundCouponIds(bookingId)) {
            // CONFIRMED 后的原承诺不因券到期或撤销资格而被重新定价。
            changed(jdbc.update("UPDATE commerce_user_booking_coupon SET status='CONSUMED' WHERE id=? AND booking_id=? AND status='RESERVED'",
                    couponId, bookingId));
        }
    }

    public void requireOnSiteAllowed(String resourceId) {
        Resource resource = resource(resourceId, false);
        if (resource.kind().equals("COURT") && !offers(resourceId, false).isEmpty()) {
            throw conflict("BOOKING_QUOTE_REQUIRED", "该场地已有固定价目，请先取得报价");
        }
    }

    private void release(String couponId, String bookingId, Instant now) {
        changed(jdbc.update("""
                UPDATE commerce_user_booking_coupon
                SET status=CASE WHEN revoked_at IS NOT NULL THEN 'REVOKED' WHEN expires_at<=? THEN 'EXPIRED' ELSE 'AVAILABLE' END,
                    booking_id=NULL WHERE id=? AND booking_id=? AND status='RESERVED'
                """, utc(now), couponId, bookingId));
    }

    private List<String> boundCouponIds(String bookingId) {
        return jdbc.queryForList("SELECT id FROM commerce_user_booking_coupon WHERE booking_id=? AND status='RESERVED' ORDER BY id", String.class, bookingId);
    }

    private List<CouponView> eligibleCoupons(long userId, long shopId, String offerId, Slot slot, String bookingId, Instant now) {
        List<CouponView> candidates = jdbc.query(COUPON_QUERY + """
                 WHERE c.user_id=? AND r.shop_id=? AND (c.status='AVAILABLE' OR (c.status='RESERVED' AND c.booking_id=?))
                 ORDER BY c.id
                """, this::joinedCouponRow, userId, shopId, bookingId);
        return candidates.stream()
                .filter(coupon -> eligible(coupon, userId, shopId, offerId, slot, bookingId, now)).toList();
    }

    private boolean eligible(CouponView coupon, long userId, long shopId, String offerId, Slot slot, String bookingId, Instant now) {
        RuleView rule = coupon.rule();
        return coupon.userId().equals(String.valueOf(userId)) && rule.shopId().equals(String.valueOf(shopId))
                && (rule.offerId() == null || rule.offerId().equals(offerId))
                && coupon.revokedAt() == null && coupon.expiresAt().isAfter(now)
                && (coupon.status().equals("AVAILABLE") || (coupon.status().equals("RESERVED") && Objects.equals(coupon.bookingId(), bookingId)))
                && !slot.start().isBefore(rule.serviceFrom()) && !slot.end().isAfter(rule.serviceTo());
    }

    private List<CouponView> selected(List<CouponView> candidates, List<String> ids) {
        List<CouponView> selected = new ArrayList<>();
        for (String id : ids) {
            selected.add(candidates.stream().filter(coupon -> coupon.id().equals(id)).findFirst()
                    .orElseThrow(() -> unavailable("所选预约券不适用当前用户、商品或服务时间")));
        }
        return selected;
    }

    private Price calculatePrice(long base, List<CouponView> coupons, boolean auto) {
        try {
            List<Coupon> inputs = coupons.stream().map(this::calculatorCoupon).toList();
            return auto ? bestPrice(base, inputs) : price(base, inputs);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw invalid("优惠计算失败：" + invalid.getMessage());
        }
    }

    private Coupon calculatorCoupon(CouponView coupon) {
        RuleView rule = coupon.rule();
        return new Coupon(coupon.id(), Type.valueOf(rule.type()), rule.thresholdCents(), rule.reductionCents(),
                rule.payFactorBps(), rule.discountCapCents(), rule.stackable(), coupon.expiresAt());
    }

    private List<DiscountView> discountViews(Price price, List<CouponView> coupons) {
        return price.discountLines().stream().map(line -> {
            RuleView rule = coupons.stream().filter(coupon -> coupon.id().equals(line.coupon().id())).findFirst().orElseThrow().rule();
            return new DiscountView(line.coupon().id(), rule.id(), rule.ruleVersion(), rule.type(),
                    rule.thresholdCents(), rule.payFactorBps(), rule.discountCapCents(), line.eligibleBaseCents(),
                    line.beforeCents(), line.discountCents(), line.afterCents(), line.coupon().expiresAt());
        }).toList();
    }

    private Map<String, Object> bookingForQuote(String bookingId, long userId, Long expectedVersion) {
        if (bookingId == null || expectedVersion == null) throw invalid("改期报价需要预约标识和版本");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM commerce_booking WHERE id=?", bookingId);
        if (rows.isEmpty()) throw missing("预约不存在");
        Map<String, Object> booking = rows.get(0);
        if (((Number) booking.get("user_id")).longValue() != userId) throw forbidden("不能为其他用户的预约报价");
        if (!"CONFIRMED".equals(booking.get("status")) || ((Number) booking.get("version")).longValue() != expectedVersion) {
            throw conflict("BOOKING_VERSION_CONFLICT", "预约已变更或不可改期，请刷新预约");
        }
        return booking;
    }

    private QuoteView quoteRow(String quoteId, boolean lock) {
        List<String> rows = jdbc.queryForList("SELECT snapshot_json FROM commerce_booking_quote WHERE id=?" + (lock ? " FOR UPDATE" : ""), String.class, quoteId);
        if (rows.isEmpty()) throw missing("报价不存在或已过期清理，请重新报价并确认新方案");
        try {
            // 历史报价快照可能带有已移除的 impressionId，读取时忽略未知字段。
            return json.readerFor(QuoteView.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(rows.get(0));
        } catch (Exception error) {
            throw new IllegalStateException("Stored booking quote is invalid", error);
        }
    }

    private Resource resource(String resourceId, boolean lock) {
        List<Resource> rows = jdbc.query("SELECT * FROM commerce_booking_resource WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Resource(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("kind"),
                        rs.getInt("max_party_size"), rs.getString("status")), id(resourceId));
        if (rows.isEmpty()) throw missing("预约资源不存在");
        return rows.get(0);
    }

    private Slot slot(String slotId) {
        List<Slot> rows = jdbc.query("SELECT * FROM commerce_booking_slot WHERE id=?", (rs, row) -> new Slot(
                rs.getLong("id"), rs.getLong("resource_id"), instant(rs, "start_at"),
                instant(rs, "end_at"), rs.getInt("capacity"), rs.getInt("occupied"), rs.getString("status")), id(slotId));
        if (rows.isEmpty()) throw missing("预约时段不存在");
        return rows.get(0);
    }

    private void validateTarget(Resource resource, Slot slot, int partySize) {
        if (slot.resourceId() != resource.id()) throw invalid("时段不属于当前资源");
        if (partySize < 1 || partySize > resource.maxPartySize()) throw invalid("预约人数超出资源接待范围");
        if (!resource.status().equals("OPEN") || !slot.status().equals("OPEN")) throw conflict("BOOKING_UNAVAILABLE", "资源或时段暂不接受新预约");
    }

    private OfferView offer(long offerId, boolean lock) {
        List<OfferView> rows = jdbc.query("SELECT * FROM commerce_booking_offer WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::offerRow, offerId);
        if (rows.isEmpty()) throw missing("价目不存在");
        return rows.get(0);
    }

    private RuleView rule(long ruleId, boolean lock) {
        List<RuleView> rows = jdbc.query("SELECT * FROM commerce_booking_coupon_rule WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::ruleRow, ruleId);
        if (rows.isEmpty()) throw missing("预约优惠规则不存在");
        return rows.get(0);
    }

    private CouponView coupon(String couponId, boolean lock) {
        List<CouponView> rows = jdbc.query("SELECT * FROM commerce_user_booking_coupon WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new CouponView(rs.getString("id"), String.valueOf(rs.getLong("user_id")), rs.getString("status"),
                        instant(rs, "expires_at"), rs.getString("booking_id"), instant(rs, "revoked_at"), rule(rs.getLong("rule_id"), false)), couponId);
        if (rows.isEmpty()) throw missing("预约优惠券不存在");
        return rows.get(0);
    }

    private OfferView offerRow(ResultSet rs, int row) throws SQLException {
        return new OfferView(String.valueOf(rs.getLong("id")), String.valueOf(rs.getLong("shop_id")),
                String.valueOf(rs.getLong("resource_id")), rs.getString("kind"), rs.getString("title"),
                rs.getString("included_items"), rs.getString("excluded_items"), rs.getLong("unit_price_cents"),
                rs.getLong("version"), rs.getString("status"));
    }

    private CouponView joinedCouponRow(ResultSet rs, int row) throws SQLException {
        return new CouponView(rs.getString("coupon_id"), String.valueOf(rs.getLong("coupon_user_id")),
                rs.getString("coupon_status"), instant(rs, "coupon_expiry"),
                rs.getString("coupon_booking_id"), instant(rs, "coupon_revoked_at"), ruleRow(rs, row));
    }

    private RuleView ruleRow(ResultSet rs, int row) throws SQLException {
        return new RuleView(String.valueOf(rs.getLong("id")), rs.getString("campaign_key"), rs.getLong("rule_version"),
                String.valueOf(rs.getLong("shop_id")), rs.getObject("offer_id") == null ? null : String.valueOf(rs.getLong("offer_id")),
                rs.getString("type"), rs.getLong("threshold_cents"), rs.getLong("reduction_cents"), rs.getInt("pay_factor_bps"),
                rs.getObject("discount_cap_cents") == null ? null : rs.getLong("discount_cap_cents"), rs.getBoolean("stackable"),
                instant(rs, "service_from"), instant(rs, "service_to"),
                instant(rs, "claim_until"), instant(rs, "coupon_expires_at"), rs.getString("issuance_status"));
    }

    private void lockUser(long userId) {
        jdbc.update("INSERT INTO commerce_booking_user_guard(user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id=user_id", userId);
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class)).toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private void validateRule(RuleRequest rule) {
        if (rule == null) throw invalid("规则请求不能为空");
        try {
            Type type = Type.valueOf(rule.type());
            new Coupon("rule", type, rule.thresholdCents(), rule.reductionCents(), rule.payFactorBps(),
                    rule.discountCapCents(), rule.stackable(), Objects.requireNonNull(rule.couponExpiresAt()));
            if (type != Type.DISCOUNT && (rule.payFactorBps() != 0 || rule.discountCapCents() != null)) throw invalid("满减和代金券不能配置折扣比例或折扣封顶");
            if (type == Type.DISCOUNT && rule.reductionCents() != 0) throw invalid("折扣券不能配置固定减免金额");
            if (rule.serviceFrom() == null || rule.serviceTo() == null || !rule.serviceTo().isAfter(rule.serviceFrom())
                    || rule.claimUntil() == null || rule.couponExpiresAt().isBefore(rule.claimUntil())) throw invalid("券的服务时间、领取截止或使用截止无效");
        } catch (IllegalArgumentException | NullPointerException error) {
            throw invalid("优惠规则参数无效：" + error.getMessage());
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encode booking price snapshot", error);
        }
    }

    private static void inTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Booking pricing mutation requires the booking transaction");
        }
    }

    private static void changed(int rows) {
        if (rows != 1) throw conflict("BOOKING_VERSION_CONFLICT", "预约价格或券状态已变化，请刷新后重试");
    }

    private static long id(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw invalid("标识必须是正整数");
        }
    }

    private static void user(long userId) {
        if (userId <= 0) throw new CommerceApiException("BOOKING_AUTH_REQUIRED", "请先登录", 401);
    }

    private static void amount(long cents) {
        if (cents < 0 || cents > MAX_CENTS) throw invalid("金额超出支持范围");
    }

    private static long checkedBase(long unitPrice, int quantity) {
        try {
            return basePrice(unitPrice, quantity);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw invalid("套餐份数与单价的乘积超出支持金额范围");
        }
    }

    private static String status(String value) {
        if (value == null) return "OPEN";
        if (!List.of("OPEN", "STOPPED").contains(value)) throw invalid("状态必须是OPEN或STOPPED");
        return value;
    }

    private static void text(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) throw invalid(label + "不能为空且长度不能超过" + maximum);
    }

    private static void optionalText(String value, int maximum, String label) {
        if (value != null && value.length() > maximum) throw invalid(label + "长度不能超过" + maximum);
    }

    private static String blank(String value) { return value == null ? "" : value; }
    private static CommerceApiException invalid(String message) { return new CommerceApiException("BOOKING_INVALID_REQUEST", message, 400); }
    private static CommerceApiException forbidden(String message) { return new CommerceApiException("BOOKING_FORBIDDEN", message, 403); }
    private static CommerceApiException missing(String message) { return new CommerceApiException("BOOKING_NOT_FOUND", message, 404); }
    private static CommerceApiException unavailable(String message) { return conflict("BOOKING_COUPON_UNAVAILABLE", message); }
    private static CommerceApiException conflict(String code, String message) { return new CommerceApiException(code, message, 409); }
    private record Resource(long id, long shopId, String kind, int maxPartySize, String status) {}
    private record Slot(long id, long resourceId, Instant start, Instant end, int capacity, int occupied, String status) {}
}
