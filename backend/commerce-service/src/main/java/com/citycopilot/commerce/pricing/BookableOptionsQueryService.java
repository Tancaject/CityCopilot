package com.citycopilot.commerce.pricing;

import com.citycopilot.commerce.CommerceApiException;
import com.citycopilot.commerce.booking.BookingPolicy;
import com.citycopilot.contract.booking.BookingContracts.ResourceView;
import com.citycopilot.contract.booking.BookingContracts.SlotView;
import com.citycopilot.contract.booking.BookingPlanContracts.*;
import com.citycopilot.contract.booking.PricingContracts.OfferView;
import com.citycopilot.contract.booking.PricingContracts.QuoteView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Core 传入已按地理条件筛选、排序的店铺；这里校验真实资源与券后价格。 */
@Service
public class BookableOptionsQueryService {
    private static final int MAX_CANDIDATES = 200;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BookingPricingService pricing;
    private final BookingPolicy policy;

    public BookableOptionsQueryService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                       BookingPricingService pricing, BookingPolicy policy) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.pricing = pricing;
        this.policy = policy;
    }

    public BatchResponse query(long userId, BatchRequest request) {
        if (userId <= 0) throw new CommerceApiException("BOOKING_AUTH_REQUIRED", "请先登录再获取个人优惠方案", 401);
        validate(request);
        if (!policy.newBookingsEnabled()) throw new CommerceApiException("BOOKING_NEW_DISABLED", "新预约暂时关闭，已有预约仍可查询和处理", 503);
        List<Long> shopIds = request.shopIds().stream().map(BookableOptionsQueryService::id).distinct().toList();
        return transactions.execute(tx -> {
            Instant now = Objects.requireNonNull(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class)).toInstant(ZoneOffset.UTC);
            if (shopIds.isEmpty()) return new BatchResponse(List.of(), now, false);
            List<BookingPricingService.PreviewInput> rows = candidates(request, shopIds, now);
            boolean truncated = rows.size() > MAX_CANDIDATES;
            List<BookingPricingService.PreviewInput> candidates = truncated ? rows.subList(0, MAX_CANDIDATES) : rows;
            List<QuoteView> previews = pricing.previews(userId, candidates, request.partySize(), now,
                    request.requestId(), request.impressionId());
            List<BatchOption> options = new ArrayList<>();
            // 先计算整个有界候选集的实际券后价；不能先取五条再过滤预算。
            for (int index = 0; index < previews.size() && options.size() < 5; index++) {
                QuoteView quote = previews.get(index);
                if (request.maxTotalCents() != null && (quote.totalCents() == null || quote.totalCents() > request.maxTotalCents())) continue;
                BookingPricingService.PreviewInput candidate = candidates.get(index);
                pricing.persistQuote(quote);
                options.add(new BatchOption(candidate.resource(), candidate.slot(), quote));
            }
            return new BatchResponse(List.copyOf(options), now, truncated);
        });
    }

    private List<BookingPricingService.PreviewInput> candidates(BatchRequest request, List<Long> shopIds, Instant now) {
        String placeholders = String.join(",", Collections.nCopies(shopIds.size(), "?"));
        String sql = """
                SELECT r.id AS resource_id,r.shop_id,r.kind AS resource_kind,r.name AS resource_name,
                       r.timezone,r.max_party_size,r.status AS resource_status,r.version AS resource_version,
                       s.id AS slot_id,s.start_at,s.end_at,s.capacity,s.occupied,s.status AS slot_status,s.version AS slot_version,
                       o.id AS offer_id,o.kind AS offer_kind,o.title,o.included_items,o.excluded_items,
                       o.unit_price_cents,o.version AS offer_version,o.status AS offer_status
                FROM commerce_booking_resource r
                JOIN commerce_booking_slot s ON s.resource_id=r.id
                LEFT JOIN commerce_booking_offer o ON o.resource_id=r.id AND o.status='OPEN'
                WHERE r.shop_id IN (%s) AND r.kind=? AND r.status='OPEN' AND s.status='OPEN'
                  AND r.max_party_size>=? AND s.start_at>=? AND s.end_at<=? AND s.start_at>?
                  AND s.capacity-s.occupied>=?
                """.formatted(placeholders);
        if (request.maxTotalCents() != null) sql += " AND o.id IS NOT NULL";
        sql += " ORDER BY FIELD(r.shop_id," + placeholders + "),s.start_at,r.id,o.id LIMIT " + (MAX_CANDIDATES + 1);
        List<Object> parameters = new ArrayList<>(shopIds);
        parameters.add(request.scenario());
        parameters.add(request.partySize());
        parameters.add(LocalDateTime.ofInstant(request.startFrom(), ZoneOffset.UTC));
        parameters.add(LocalDateTime.ofInstant(request.endBefore(), ZoneOffset.UTC));
        parameters.add(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        parameters.add(request.scenario().equals("COURT") ? 1 : request.partySize());
        parameters.addAll(shopIds);
        return jdbc.query(sql, this::candidateRow, parameters.toArray());
    }

    private BookingPricingService.PreviewInput candidateRow(ResultSet rs, int row) throws SQLException {
        String resourceId = String.valueOf(rs.getLong("resource_id"));
        String shopId = String.valueOf(rs.getLong("shop_id"));
        ResourceView resource = new ResourceView(resourceId, shopId, rs.getString("resource_kind"),
                rs.getString("resource_name"), rs.getString("timezone"), rs.getInt("max_party_size"),
                rs.getString("resource_status"), rs.getLong("resource_version"));
        SlotView slot = new SlotView(String.valueOf(rs.getLong("slot_id")), resourceId,
                rs.getObject("start_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("end_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getInt("capacity"),
                rs.getInt("occupied"), rs.getInt("capacity") - rs.getInt("occupied"),
                rs.getString("slot_status"), rs.getLong("slot_version"));
        OfferView offer = rs.getObject("offer_id") == null ? null : new OfferView(String.valueOf(rs.getLong("offer_id")),
                shopId, resourceId, rs.getString("offer_kind"), rs.getString("title"), rs.getString("included_items"),
                rs.getString("excluded_items"), rs.getLong("unit_price_cents"), rs.getLong("offer_version"), rs.getString("offer_status"));
        return new BookingPricingService.PreviewInput(resource, slot, offer);
    }

    private void validate(BatchRequest request) {
        if (request == null || request.shopIds() == null || request.shopIds().size() > 50) invalid("一次最多查询50家候选店铺");
        if (!"COURT".equals(request.scenario()) && !"DINING_POOL".equals(request.scenario())) invalid("预约场景必须是COURT或DINING_POOL");
        if (request.startFrom() == null || request.endBefore() == null || !request.endBefore().isAfter(request.startFrom())
                || Duration.between(request.startFrom(), request.endBefore()).compareTo(Duration.ofDays(policy.publishingDays())) > 0) invalid("预约查询时间窗口无效或超出发布窗口");
        if (request.partySize() < 1 || request.scenario().equals("DINING_POOL") && request.partySize() > policy.maxDiningPartySize()) invalid("预约人数超出场景允许范围");
        if (request.maxTotalCents() != null && (request.maxTotalCents() < 0 || request.maxTotalCents() > BookingPriceCalculator.MAX_CENTS)) invalid("总预算超出支持金额范围");
        if (request.requestId() != null && request.requestId().length() > 128 || request.impressionId() != null && request.impressionId().length() > 128) invalid("归因标识过长");
    }

    private static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException error) {
            throw new CommerceApiException("BOOKING_INVALID_REQUEST", "店铺标识必须是正整数", 400);
        }
    }

    private static void invalid(String message) {
        throw new CommerceApiException("BOOKING_INVALID_REQUEST", message, 400);
    }
}
