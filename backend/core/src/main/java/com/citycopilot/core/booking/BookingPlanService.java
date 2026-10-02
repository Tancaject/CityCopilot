package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingPlanContracts.*;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.service.discovery.LocalShopDiscoveryProvider;
import com.citycopilot.core.service.discovery.ShopDiscoveryQuery;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Local discovery supplies shops; Commerce supplies current capacity and deterministic prices. */
@Service
public class BookingPlanService {
    static final String RANKING_VERSION = "booking-rules-v2";
    private final LocalShopDiscoveryProvider discovery;
    private final BookingPortClient bookings;
    private final BookingFeedbackService feedback;

    public BookingPlanService(LocalShopDiscoveryProvider discovery, BookingPortClient bookings, BookingFeedbackService feedback) {
        this.discovery = discovery;
        this.bookings = bookings;
        this.feedback = feedback;
    }

    @AgentTool(name = "search_bookable_options", audience = ToolAudience.CONSUMER, scope = "booking",
            description = "Find real local booking plans. request.scenario is COURT or DINING_POOL; date is YYYY-MM-DD, timeFrom/timeTo are HH:mm, partySize is required. "
                    + "Budget is integer CNY cents with explicit TOTAL or PER_PERSON basis. Return clarifications for missing constraints. This tool never reserves capacity.")
    public PlanResponse plan(PlanRequest request) {
        String requestId = UUID.randomUUID().toString();
        var rules = bookings.rules();
        var questions = questions(request);
        if (!questions.isEmpty()) return new PlanResponse("NEEDS_INPUT", requestId, RANKING_VERSION, rules.serverTime(),
                questions, List.of(), 0, false, "请补充预约条件后再查询。");
        if (UserHolder.getUser() == null || UserHolder.getUser().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请登录后查询自己的优惠报价");
        }
        ZoneId zone;
        try { zone = ZoneId.of(request.timezone() == null ? rules.defaultTimezone() : request.timezone()); }
        catch (DateTimeException error) { throw invalid("时区名称无效，请选择有效的 IANA 时区"); }
        LocalDateTime localStart = request.date().atTime(request.timeFrom());
        LocalDateTime localEnd = request.date().atTime(request.timeTo());
        if (request.timeFrom().equals(request.timeTo())) throw invalid("查询结束时间不能等于开始时间");
        if (localEnd.isBefore(localStart)) localEnd = localEnd.plusDays(1);
        Instant start = unambiguous(localStart, zone);
        Instant end = unambiguous(localEnd, zone);
        if (!end.isAfter(rules.serverTime())) throw invalid("查询时段已经结束，请选择未来日期");
        if (start.isAfter(rules.serverTime().plus(Duration.ofDays(rules.publishingDays())))) {
            throw invalid("只能查询已开放的未来 " + rules.publishingDays() + " 天");
        }
        if (request.partySize() < 1 || request.partySize() > 1000
                || request.scenario().equals("DINING_POOL") && request.partySize() > rules.maxDiningPartySize()) {
            throw invalid("预约人数超出该场景允许范围");
        }
        validateLocation(request);
        List<String> preferences = request.preferences() == null ? List.of() : request.preferences();
        if (preferences.size() > 8 || preferences.stream().anyMatch(p -> p == null || p.length() > 40)) throw invalid("偏好最多8项，每项不超过40字");
        if (request.keyword() != null && request.keyword().length() > 100) throw invalid("搜索词不能超过100字");
        Long budget = totalBudget(request);
        var query = new ShopDiscoveryQuery().setCurrent(1).setSize(51).setKeyword(request.keyword())
                .setX(request.longitude()).setY(request.latitude()).setRadiusMeters(request.maxDistanceMeters());
        List<ShopDiscoveryItemDTO> found = discovery.discover(query);
        var eligible = found.stream().filter(shop -> shop.getId() != null && shop.isOrderable() && "local".equals(shop.getSource()))
                .filter(shop -> request.maxDistanceMeters() == null || shop.getDistance() != null && shop.getDistance() <= request.maxDistanceMeters())
                .limit(50).toList();
        long userId = UserHolder.getUser().getId();
        var features = feedback.features(userId, eligible.stream().map(shop -> shop.getId().toString()).distinct().toList());
        var shops = eligible.stream().sorted(ranking(preferences, features)).toList();
        if (shops.isEmpty()) return new PlanResponse("NO_MATCH", requestId, RANKING_VERSION, rules.serverTime(),
                List.of(), List.of(), 0, false, "当前检索范围没有符合条件的本地店铺，请调整关键词或距离。");
        Map<String, ShopDiscoveryItemDTO> byId = shops.stream().collect(Collectors.toMap(s -> s.getId().toString(), s -> s, (first, duplicate) -> first));
        String impressionId = feedback.rules().enabled() ? UUID.randomUUID().toString() : null;
        var batch = bookings.options(new BatchRequest(shops.stream().map(s -> s.getId().toString()).distinct().toList(),
                request.scenario(), start, end, request.partySize(), budget, requestId, impressionId));
        var options = batch.options().stream().map(option -> {
            var shop = byId.get(option.resource().shopId());
            if (shop == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "预约候选与店铺检索结果不一致，请重新查询");
            List<String> reasons = new ArrayList<>();
            if (preferenceMatches(shop, preferences) > 0) reasons.add("符合你明确填写的偏好");
            var feature = features.get(shop.getId().toString());
            if (feature != null && feature.negative()) reasons.add("你曾标记不感兴趣，该店铺已降低排序");
            else if (feature != null && feature.used() > 0) reasons.add("参考了你近期在该店铺的核销记录");
            else if (feature != null && feature.favorites() > 0) reasons.add("参考了你近期对该店铺的收藏");
            if (shop.getDistance() != null) reasons.add("距离为直线距离，不代表步行时间");
            reasons.add(option.resource().kind().equals("COURT") ? "按具体场地时段预约" : "按人数预留接待名额");
            if (option.quote().totalCents() == null) reasons.add("纯订位，消费金额到店确定");
            else if (budget != null) reasons.add("套餐或场地报价符合总额预算");
            return new PlanOption(option.resource().shopId(), shop.getName(), shop.getAddress(), shop.getDistance(),
                    option.resource(), option.slot(), option.quote(), reasons);
        }).toList();
        if (impressionId != null && !options.isEmpty()) {
            feedback.issueImpression(impressionId, userId, options.stream().map(PlanOption::shopId).distinct().toList(), RANKING_VERSION);
        }
        return new PlanResponse(options.isEmpty() ? "NO_MATCH" : "OPTIONS", requestId, RANKING_VERSION,
                batch.serverTime(), List.of(), options, shops.size(), batch.truncated() || found.size() > 50,
                options.isEmpty() ? "当前本地候选中没有同时满足人数、时段和预算的预约方案。"
                        : "以下方案来自当前本地候选；选择暂留后才会占用名额。");
    }

    private List<Clarification> questions(PlanRequest request) {
        var result = new ArrayList<Clarification>();
        if (request == null || request.scenario() == null) result.add(new Clarification("scenario", "要预约运动场馆还是餐厅？"));
        else if (!Set.of("COURT", "DINING_POOL").contains(request.scenario())) throw invalid("预约场景必须为COURT或DINING_POOL");
        if (request == null || request.date() == null) result.add(new Clarification("date", "计划哪一天到店？"));
        if (request == null || request.timeFrom() == null || request.timeTo() == null) result.add(new Clarification("time", "可接受的开始和结束时间是什么？"));
        if (request == null || request.partySize() == null) result.add(new Clarification("partySize", "一共几个人？"));
        if (request != null && request.budgetCents() != null && request.budgetBasis() == null) {
            result.add(new Clarification("budgetBasis", "预算指总额还是每人？"));
        }
        if (request != null && request.maxDistanceMeters() != null && (request.longitude() == null || request.latitude() == null)) {
            result.add(new Clarification("location", "请提供查询中心的位置，以便按距离筛选。"));
        }
        return List.copyOf(result);
    }

    private Long totalBudget(PlanRequest request) {
        if (request.budgetCents() == null) {
            if (request.budgetBasis() != null) throw invalid("填写预算口径时也需要填写预算金额");
            return null;
        }
        if (request.budgetCents() < 0 || request.budgetCents() > 922_337_203_685_477L) throw invalid("预算金额超出允许范围");
        if (!Set.of("TOTAL", "PER_PERSON").contains(request.budgetBasis())) throw invalid("预算口径必须为TOTAL或PER_PERSON");
        try {
            long total = request.budgetBasis().equals("PER_PERSON") ? Math.multiplyExact(request.budgetCents(), request.partySize()) : request.budgetCents();
            if (total > 922_337_203_685_477L) throw invalid("总预算超出支持金额范围，请调整金额或人数");
            return total;
        } catch (ArithmeticException error) { throw invalid("总预算过大，请调整金额或人数"); }
    }

    private void validateLocation(PlanRequest request) {
        if ((request.longitude() == null) != (request.latitude() == null)) throw invalid("经度和纬度必须同时填写");
        if (request.longitude() != null && (!Double.isFinite(request.longitude()) || !Double.isFinite(request.latitude())
                || Math.abs(request.longitude()) > 180 || Math.abs(request.latitude()) > 85.05112878)) throw invalid("经纬度超出支持范围");
        if (request.maxDistanceMeters() != null && (!Double.isFinite(request.maxDistanceMeters()) || request.maxDistanceMeters() <= 0
                || request.maxDistanceMeters() > 100_000)) throw invalid("查询距离须在0到100000米之间");
    }

    private static int preferenceMatches(ShopDiscoveryItemDTO shop, List<String> preferences) {
        String text = (Objects.toString(shop.getName(), "") + " " + Objects.toString(shop.getTypeName(), "") + " "
                + Objects.toString(shop.getTag(), "")).toLowerCase(Locale.ROOT);
        return (int) preferences.stream().filter(p -> !p.isBlank() && text.contains(p.toLowerCase(Locale.ROOT))).count();
    }

    static Comparator<ShopDiscoveryItemDTO> ranking(List<String> preferences, Map<String, BookingFeedbackService.ShopFeature> features) {
        return Comparator.comparing((ShopDiscoveryItemDTO shop) -> {
                    var feature = features.get(shop.getId().toString());
                    return feature != null && feature.negative();
                })
                .thenComparingDouble(shop -> -rankingScore(shop, preferences, features.get(shop.getId().toString())))
                .thenComparing(ShopDiscoveryItemDTO::getId);
    }

    static double rankingScore(ShopDiscoveryItemDTO shop, List<String> preferences, BookingFeedbackService.ShopFeature feature) {
        double relevance = shop.getSearchRank() == null ? 0 : 2d / (1 + Math.max(0, shop.getSearchRank()));
        double distance = shop.getDistance() == null ? 0 : 2d / (1 + Math.max(0, shop.getDistance()) / 1000);
        double quality = shop.getScore() == null ? 0 : Math.min(50, Math.max(0, shop.getScore())) / 50d;
        // ponytail: bounded rule weights; revise only against the fixed labelled ranking set.
        double behavior = feature == null ? 0 : Math.min(2, feature.used()) + Math.min(1, feature.favorites()) * 0.5;
        return 4 * preferenceMatches(shop, preferences) + relevance + distance + quality + behavior;
    }

    private static Instant unambiguous(LocalDateTime local, ZoneId zone) {
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw invalid("所选时间遇到夏令时切换，请选择该时区中不歧义的时段");
        return local.toInstant(offsets.get(0));
    }

    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
