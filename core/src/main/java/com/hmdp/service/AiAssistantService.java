package com.hmdp.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.config.AiProperties;
import com.hmdp.config.GeoProperties;
import com.hmdp.dto.AiAssistantActionDTO;
import com.hmdp.dto.AiAssistantShopDTO;
import com.hmdp.dto.AiChatMessageDTO;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopGeoBatchJob;
import com.hmdp.entity.ShopGeoBatchJobItem;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.Voucher;
import com.hmdp.security.PermissionCodes;
import com.hmdp.security.PermissionPolicy;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.AI_RATE_LIMIT_KEY;

@Slf4j
@Service
@Deprecated(since = "0.1-citycopilot", forRemoval = false)
public class AiAssistantService {

    private static final DateTimeFormatter RATE_BUCKET_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final Pattern PRICE_PATTERN = Pattern.compile("(?:人均|预算|低于|不超过|以内|小于|少于)\\D{0,6}(\\d{2,4})");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d{2,4}");
    private static final Set<String> STOP_WORDS = Set.of(
            "附近", "推荐", "帮我", "找", "一下", "一个", "一些", "有没有", "有什么", "适合", "今天",
            "想吃", "想去", "人均", "以内", "低于", "不超过", "评分", "高", "有券", "优惠券", "店铺",
            "店", "家", "哪家", "按", "距离", "排序", "比较", "可以", "最好", "现在", "开门",
            "的", "更", "一点", "便宜点", "换一家", "再来", "来点"
    );
    private static final Map<String, List<String>> TYPE_ALIASES = Map.ofEntries(
            Map.entry("美食", List.of("吃", "餐厅", "火锅", "咖啡", "奶茶", "甜品", "烧烤", "日料", "西餐", "小吃", "面馆", "宵夜")),
            Map.entry("KTV", List.of("唱歌", "包厢", "麦霸")),
            Map.entry("丽人·美发", List.of("理发", "剪发", "烫发", "染发", "美发", "造型")),
            Map.entry("健身运动", List.of("健身", "瑜伽", "游泳", "运动", "私教")),
            Map.entry("按摩·足疗", List.of("按摩", "足疗", "推拿", "SPA", "放松")),
            Map.entry("美容SPA", List.of("美容", "护肤", "spa", "SPA")),
            Map.entry("亲子游乐", List.of("亲子", "儿童", "游乐", "遛娃")),
            Map.entry("酒吧", List.of("酒吧", "喝酒", "小酌", "夜生活")),
            Map.entry("轰趴馆", List.of("轰趴", "聚会", "团建", "派对")),
            Map.entry("美睫·美甲", List.of("美甲", "美睫", "甲片", "睫毛"))
    );

    private final RestTemplate restTemplate;
    private final AiProperties aiProperties;

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private ShopGeoBatchService shopGeoBatchService;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private AmapGeoService amapGeoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PermissionPolicy permissionPolicy;

    @Resource
    private GeoProperties geoProperties;

    public AiAssistantService(RestTemplateBuilder builder, AiProperties aiProperties) {
        this.aiProperties = aiProperties;
        int timeoutSeconds = positive(aiProperties.getTimeoutSeconds(), 8);
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(Math.min(3, timeoutSeconds)))
                .setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    public AiChatResponse chat(AiChatRequest request) {
        AiChatRequest safeRequest = normalizeRequest(request);
        if (StrUtil.isBlank(safeRequest.getMessage())) {
            return baseResponse(safeRequest, "user", "empty", 1D)
                    .setMessage("你可以直接说想找什么店，比如“附近人均 100 以内、有优惠券的火锅”。");
        }

        AiChatResponse blocked = guardRequest(safeRequest);
        if (blocked != null) {
            operationLogService.record("ai", "ai_chat_blocked", "ai_assistant", null, blocked.getIntent(), safeRequest, blocked, true, null);
            return blocked;
        }

        AiChatResponse response = wantsAdmin(safeRequest.getMessage(), safeRequest)
                ? answerAdminQuestion(safeRequest)
                : answerShopQuestion(safeRequest);
        response.setMessage(polishWithModel(safeRequest.getMessage(), response));
        operationLogService.record("ai", "ai_chat", "ai_assistant", null, response.getIntent(), safeRequest, response, true, null);
        return response;
    }

    private AiChatResponse answerShopQuestion(AiChatRequest request) {
        AiChatResponse currentShopAnswer = answerCurrentShopQuestion(request);
        if (currentShopAnswer != null) {
            return currentShopAnswer;
        }

        String userText = request.getMessage().trim();
        String text = contextualText(request);
        List<ShopType> shopTypes = safeShopTypes();
        ShopType matchedType = matchType(text, shopTypes);
        Long typeId = matchedType == null ? null : matchedType.getId();
        Integer maxPrice = extractMaxPrice(text);
        boolean asksNearby = text.contains("附近");
        boolean wantsVoucher = text.contains("券") || text.contains("优惠");
        boolean wantsCheap = text.contains("便宜") || text.contains("划算") || text.contains("性价比");
        boolean wantsCompare = text.contains("比较") || text.contains("哪个好") || text.contains("哪家");

        List<Shop> candidates = loadCandidateShops(typeId, request, asksNearby);
        Map<Long, List<Voucher>> voucherMap = vouchersByShop(candidates, 3);
        List<ScoredShop> scored = candidates.stream()
                .filter(shop -> maxPrice == null || shop.getAvgPrice() == null || shop.getAvgPrice() <= maxPrice)
                .map(shop -> scoreShop(shop, text, maxPrice, wantsVoucher, wantsCheap, voucherMap.getOrDefault(shop.getId(), List.of())))
                .filter(item -> item.score() > 0.2D)
                .sorted(Comparator.comparingDouble(ScoredShop::score).reversed())
                .limit(5)
                .toList();

        if (scored.isEmpty() && maxPrice != null) {
            scored = candidates.stream()
                    .map(shop -> scoreShop(shop, text, null, wantsVoucher, wantsCheap, voucherMap.getOrDefault(shop.getId(), List.of())))
                    .sorted(Comparator.comparingDouble(ScoredShop::score).reversed())
                    .limit(5)
                    .toList();
        }

        List<AiAssistantShopDTO> shops = scored.stream()
                .map(item -> toShopDTO(item.shop(), item.reason(), voucherMap))
                .toList();

        AiChatResponse response = baseResponse(request, "user", inferShopIntent(wantsVoucher, wantsCheap, wantsCompare), shops.isEmpty() ? 0.45D : 0.78D)
                .setShops(shops);
        response.getDiagnostics().put("typeId", typeId);
        response.getDiagnostics().put("typeName", matchedType == null ? null : matchedType.getName());
        response.getDiagnostics().put("maxPrice", maxPrice);
        response.getDiagnostics().put("nearby", hasCoordinate(request));
        response.getDiagnostics().put("historyUsed", hasHistory(request));

        if (shops.isEmpty()) {
            response.setMessage("我没有从现有店铺里匹配到足够明确的结果。你可以补充品类、人均预算或是否需要附近优先。");
        } else {
            response.setMessage(buildShopAnswer(shops, matchedType, maxPrice, hasCoordinate(request), asksNearby));
            response.getActions().add(shopListAction(userText, matchedType));
        }
        if (asksNearby && !hasCoordinate(request)) {
            response.setMessage("我还没拿到你的定位，所以先按全城综合数据筛选。" + response.getMessage());
            response.getActions().add(0, locateAction(userText));
        }
        return response;
    }

    private AiChatResponse answerCurrentShopQuestion(AiChatRequest request) {
        if (request.getCurrentShopId() == null || !isCurrentShopIntent(request.getMessage())) {
            return null;
        }
        Shop shop = shopService.getById(request.getCurrentShopId());
        if (shop == null) {
            return null;
        }
        if ((request.getMessage().contains("类似") || request.getMessage().contains("附近")) && shop.getTypeId() != null) {
            AiChatResponse similar = answerSimilarShopQuestion(request, shop);
            if (similar != null) {
                return similar;
            }
        }

        List<Voucher> vouchers = vouchersOf(shop.getId(), 5);
        String message = buildCurrentShopAnswer(shop, vouchers, request.getMessage());
        AiChatResponse response = baseResponse(request, "user", "current_shop_advice", 0.86D)
                .setMessage(message)
                .setShops(List.of(toShopDTO(shop, "当前浏览店铺", Map.of(shop.getId(), vouchers))));
        response.getActions().add(new AiAssistantActionDTO()
                .setLabel("打开店铺详情")
                .setType("route")
                .setRoutePath("/shops/" + shop.getId()));
        if (shop.getTypeId() != null) {
            response.getActions().add(new AiAssistantActionDTO()
                    .setLabel("看看同类店铺")
                    .setType("route")
                    .setRouteName("shops")
                    .setQuery(Map.of("typeId", shop.getTypeId())));
        }
        return response;
    }

    private AiChatResponse answerSimilarShopQuestion(AiChatRequest request, Shop currentShop) {
        boolean asksNearby = request.getMessage().contains("附近");
        List<Shop> candidates = loadCandidateShops(currentShop.getTypeId(), request, asksNearby).stream()
                .filter(shop -> !Objects.equals(shop.getId(), currentShop.getId()))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }

        Map<Long, List<Voucher>> voucherMap = vouchersByShop(candidates, 2);
        List<AiAssistantShopDTO> shops = candidates.stream()
                .map(shop -> scoreShop(shop, contextualText(request), null, request.getMessage().contains("券"), false, voucherMap.getOrDefault(shop.getId(), List.of())))
                .sorted(Comparator.comparingDouble(ScoredShop::score).reversed())
                .limit(4)
                .map(item -> toShopDTO(item.shop(), "与当前店同类，" + item.reason(), voucherMap))
                .toList();

        AiChatResponse response = baseResponse(request, "user", "similar_shop_recommendation", 0.8D)
                .setShops(shops)
                .setMessage("我按当前店铺的同类目筛了 " + shops.size() + " 家可比较的店。可以先看第一家 "
                        + shops.get(0).getName() + "，理由是：" + shops.get(0).getReason() + "。");
        response.getActions().add(new AiAssistantActionDTO()
                .setLabel("回到当前店")
                .setType("route")
                .setRoutePath("/shops/" + currentShop.getId()));
        if (asksNearby && !hasCoordinate(request)) {
            response.setMessage("我还没拿到你的定位，所以先按同类店综合数据筛选。" + response.getMessage());
            response.getActions().add(0, locateAction(request.getMessage()));
        }
        return response;
    }

    private AiChatResponse answerAdminQuestion(AiChatRequest request) {
        UserDTO user = UserHolder.getUser();
        if (user == null || !permissionPolicy.hasPermission(user.getRole(), PermissionCodes.GEO_READ)) {
            return baseResponse(request, "admin", "admin_permission_required", 1D)
                    .setMessage("这个问题涉及运营数据，需要具备运营权限后使用。");
        }

        Long missingCount = shopService.query()
                .and(wrapper -> wrapper.isNull("x").or().isNull("y"))
                .count();
        Long pendingCount = shopService.query()
                .eq("geo_status", "pending")
                .count();
        Long verifiedCount = shopService.query()
                .eq("geo_status", "verified")
                .count();
        List<Shop> missingShops = shopService.query()
                .and(wrapper -> wrapper.isNull("x").or().isNull("y"))
                .last("LIMIT 5")
                .list();
        Page<ShopGeoBatchJob> recentJobs = shopGeoBatchService.queryJobs(1, null);
        List<ShopGeoBatchJob> failedJobs = recentJobs.getRecords().stream()
                .filter(job -> job.getFailed() != null && job.getFailed() > 0)
                .limit(3)
                .toList();
        Map<String, Object> amapStatus = safeAmapStatus();

        AiChatResponse response = baseResponse(request, "admin", "geo_operations_analysis", 0.86D)
                .setMessage(buildAdminAnswer(missingCount, pendingCount, verifiedCount, failedJobs, amapStatus))
                .setShops(missingShops.stream().map(shop -> toShopDTO(shop, "缺少坐标，适合进入下一批校准")).toList());
        response.getDiagnostics().put("missing", missingCount);
        response.getDiagnostics().put("pending", pendingCount);
        response.getDiagnostics().put("verified", verifiedCount);
        response.getDiagnostics().put("failedJobs", failedJobs.stream().map(ShopGeoBatchJob::getJobId).toList());
        response.getDiagnostics().put("amap", amapStatus);
        response.getActions().add(new AiAssistantActionDTO()
                .setLabel("打开地理校准台")
                .setType("route")
                .setRouteName("geo-shops-admin"));
        if (!isAmapCircuitOpen(amapStatus) && missingCount != null && missingCount > 0) {
            response.getActions().add(new AiAssistantActionDTO()
                    .setLabel("创建缺坐标批量任务")
                    .setType("geo_batch_start")
                    .setQuery(Map.of("city", defaultCity(), "onlyMissing", true, "verified", false, "limit", 20)));
        }
        if (verifiedCount != null && verifiedCount > 0) {
            response.getActions().add(new AiAssistantActionDTO()
                    .setLabel("重建 GEO 索引")
                    .setType("geo_index_sync"));
        }
        if (!failedJobs.isEmpty()) {
            response.getActions().add(new AiAssistantActionDTO()
                    .setLabel("查看失败批量任务")
                    .setType("route")
                    .setRouteName("geo-shops-admin")
                    .setQuery(Map.of("focus", "failed-jobs")));
            response.getActions().add(new AiAssistantActionDTO()
                    .setLabel("重试最近失败任务")
                    .setType("geo_batch_retry")
                    .setQuery(Map.of("jobId", failedJobs.get(0).getJobId(), "verified", false, "limit", 20)));
            response.getDiagnostics().put("failureSamples", failureSamples(failedJobs.get(0).getJobId()));
        }
        return response;
    }

    private AiChatResponse guardRequest(AiChatRequest request) {
        int maxLength = positive(aiProperties.getMaxMessageLength(), 300);
        if (request.getMessage().length() > maxLength) {
            return baseResponse(request, request.getScene(), "message_too_long", 1D)
                    .setMessage("这次问题太长了，我先不处理。请把问题控制在 " + maxLength + " 字以内，直接说品类、预算、位置或运营目标就行。");
        }
        if (looksLikePromptAttack(request.getMessage())) {
            return baseResponse(request, request.getScene(), "unsafe_prompt", 1D)
                    .setMessage("我不能处理泄露密钥、系统提示词或绕过业务规则的请求。可以继续问店铺推荐、优惠券或运营校准问题。");
        }
        if (isRateLimited(request)) {
            return baseResponse(request, request.getScene(), "rate_limited", 1D)
                    .setMessage("请求有点密集，我先帮系统缓一口气。等一分钟后再问，推荐和运营数据会更稳。");
        }
        return null;
    }

    private boolean wantsAdmin(String message, AiChatRequest request) {
        if ("admin".equals(request.getScene())) {
            return true;
        }
        return message.contains("缺坐标")
                || message.contains("批量")
                || message.contains("校准")
                || message.contains("审计")
                || message.contains("失败任务")
                || message.contains("高德服务")
                || message.contains("pending");
    }

    private List<Shop> loadCandidateShops(Long typeId, AiChatRequest request, boolean asksNearby) {
        if (hasCoordinate(request) && (asksNearby || typeId != null)) {
            Result result = typeId == null
                    ? shopService.queryNearbyShops(1, null, request.getX(), request.getY())
                    : shopService.queryShopByType(typeId.intValue(), 1, null, request.getX(), request.getY());
            List<Shop> nearby = extractShopList(result);
            if (!nearby.isEmpty()) {
                return nearby;
            }
        }
        return shopService.query()
                .eq(typeId != null, "type_id", typeId)
                .orderByDesc("score")
                .orderByDesc("sold")
                .last("LIMIT 60")
                .list();
    }

    @SuppressWarnings("unchecked")
    private List<Shop> extractShopList(Result result) {
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || !(result.getData() instanceof List<?> list)) {
            return List.of();
        }
        return (List<Shop>) list;
    }

    private ScoredShop scoreShop(Shop shop, String text, Integer maxPrice, boolean wantsVoucher, boolean wantsCheap, List<Voucher> vouchers) {
        double score = 1D;
        List<String> reasons = new ArrayList<>();
        if (shop.getScore() != null) {
            score += shop.getScore() / 10D;
            if (shop.getScore() >= 45) {
                reasons.add("评分高");
            }
        }
        if (shop.getSold() != null) {
            score += Math.min(2D, shop.getSold() / 500D);
        }
        if (shop.getComments() != null) {
            score += Math.min(1.5D, shop.getComments() / 600D);
        }
        if (shop.getDistance() != null) {
            score += Math.max(0D, 3D - shop.getDistance() / 1000D);
            reasons.add("距离较近");
        }
        if (maxPrice != null && shop.getAvgPrice() != null && shop.getAvgPrice() <= maxPrice) {
            score += 2D;
            reasons.add("在预算内");
        }
        if (wantsCheap && shop.getAvgPrice() != null) {
            score += Math.max(0D, 2D - shop.getAvgPrice() / 100D);
            reasons.add("价格更友好");
        }
        if (StrUtil.isNotBlank(shop.getName()) && text.contains(shop.getName())) {
            score += 5D;
            reasons.add("店名精确匹配");
        }
        if (StrUtil.isNotBlank(shop.getArea()) && text.contains(shop.getArea())) {
            score += 1.5D;
            reasons.add("商圈匹配");
        }
        if (wantsVoucher && !vouchers.isEmpty()) {
            score += 2D;
            reasons.add("有可用优惠券");
        }
        for (String token : extractTokens(text)) {
            if (containsIgnoreBlank(shop.getName(), token) || containsIgnoreBlank(shop.getAddress(), token) || containsIgnoreBlank(shop.getArea(), token)) {
                score += 1.4D;
                reasons.add("匹配“" + token + "”");
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("综合热度和评分靠前");
        }
        return new ScoredShop(shop, score, reasons.stream().distinct().limit(3).collect(Collectors.joining("、")));
    }

    private AiAssistantShopDTO toShopDTO(Shop shop, String reason) {
        return toShopDTO(shop, reason, Map.of(shop.getId(), vouchersOf(shop.getId(), 3)));
    }

    private AiAssistantShopDTO toShopDTO(Shop shop, String reason, Map<Long, List<Voucher>> voucherMap) {
        List<Voucher> vouchers = voucherMap.getOrDefault(shop.getId(), List.of());
        return new AiAssistantShopDTO()
                .setId(shop.getId())
                .setName(shop.getName())
                .setArea(shop.getArea())
                .setAddress(shop.getAddress())
                .setAvgPrice(shop.getAvgPrice())
                .setScore(shop.getScore())
                .setSold(shop.getSold())
                .setComments(shop.getComments())
                .setOpenHours(shop.getOpenHours())
                .setDistance(shop.getDistance())
                .setVoucherCount(vouchers.size())
                .setVoucherHighlights(vouchers.stream().map(Voucher::getTitle).limit(2).toList())
                .setReason(reason);
    }

    private Map<Long, List<Voucher>> vouchersByShop(List<Shop> shops, int limitPerShop) {
        List<Long> shopIds = shops.stream()
                .map(Shop::getId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (shopIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, List<Voucher>> grouped = voucherService.list(new QueryWrapper<Voucher>()
                            .in("shop_id", shopIds)
                            .orderByDesc("id"))
                    .stream()
                    .collect(Collectors.groupingBy(Voucher::getShopId));
            grouped.replaceAll((shopId, vouchers) -> vouchers.stream().limit(limitPerShop).toList());
            return grouped;
        } catch (Exception error) {
            log.debug("Load vouchers for AI skipped: {}", error.getMessage());
            return Map.of();
        }
    }

    private List<Voucher> vouchersOf(Long shopId, int limit) {
        if (shopId == null) {
            return List.of();
        }
        return voucherService.list(new QueryWrapper<Voucher>()
                .eq("shop_id", shopId)
                .last("LIMIT " + limit));
    }

    private String buildShopAnswer(List<AiAssistantShopDTO> shops, ShopType type, Integer maxPrice, boolean hasLocation, boolean asksNearby) {
        List<String> filters = new ArrayList<>();
        if (type != null) {
            filters.add(type.getName());
        }
        if (maxPrice != null) {
            filters.add("人均不超过 " + maxPrice);
        }
        if (hasLocation && asksNearby) {
            filters.add("附近优先");
        }
        String prefix = filters.isEmpty() ? "我按综合评分、热度和优惠信息" : "我按 " + String.join("、", filters);
        return prefix + "从真实店铺数据里筛了 " + shops.size() + " 家。排第一的是 "
                + shops.get(0).getName() + "，理由是：" + shops.get(0).getReason() + "。";
    }

    private String buildCurrentShopAnswer(Shop shop, List<Voucher> vouchers, String text) {
        List<String> highlights = new ArrayList<>();
        if (shop.getScore() != null) {
            highlights.add("评分 " + (shop.getScore() / 10D));
        }
        if (shop.getAvgPrice() != null) {
            highlights.add("人均约 " + shop.getAvgPrice() + " 元");
        }
        if (shop.getOpenHours() != null) {
            highlights.add("营业时间 " + shop.getOpenHours());
        }
        String prefix = highlights.isEmpty()
                ? shop.getName() + " 当前资料还比较少"
                : shop.getName() + "：" + String.join("，", highlights);
        if (text.contains("券") || text.contains("优惠")) {
            if (vouchers.isEmpty()) {
                return prefix + "。我现在没查到这家店的可用优惠券。";
            }
            return prefix + "。可用券包括：" + vouchers.stream().map(Voucher::getTitle).limit(3).collect(Collectors.joining("、")) + "。";
        }
        return prefix + "。从当前数据看，可以重点参考评分、人均和优惠券，再结合详情页图片与地址确认是否合适。";
    }

    private String buildAdminAnswer(Long missing, Long pending, Long verified, List<ShopGeoBatchJob> failedJobs, Map<String, Object> amapStatus) {
        StringBuilder builder = new StringBuilder();
        builder.append("当前地理数据状态：缺坐标 ").append(missing)
                .append(" 家，待确认 ").append(pending)
                .append(" 家，已验证 ").append(verified).append(" 家。");
        if (isAmapCircuitOpen(amapStatus)) {
            builder.append(" 高德服务正在熔断，约 ").append(amapStatus.getOrDefault("circuitTtlSeconds", 0)).append(" 秒后恢复，建议先暂停批量任务。");
        } else if (!failedJobs.isEmpty()) {
            builder.append(" 最近有 ").append(failedJobs.size()).append(" 个批量任务存在失败明细，建议先查看失败原因，再重试失败项。");
        } else if (missing != null && missing > 0) {
            builder.append(" 建议下一步先按缺坐标店铺创建批量校准任务，单批控制在配置上限内。");
        } else if (pending != null && pending > 0) {
            builder.append(" 建议优先人工验证 pending 店铺，确认后再重建 Redis GEO 索引。");
        } else {
            builder.append(" 当前地理数据比较健康，可以定期抽查审计日志和高德服务状态。");
        }
        Integer currentCalls = intValue(amapStatus.get("currentMinuteCalls"));
        Integer rateLimit = intValue(amapStatus.get("rateLimitPerMinute"));
        if (currentCalls != null && rateLimit != null && rateLimit > 0) {
            builder.append(" 当前高德分钟调用量 ").append(currentCalls).append("/").append(rateLimit).append("。");
        }
        return builder.toString();
    }

    private AiAssistantActionDTO locateAction(String prompt) {
        return new AiAssistantActionDTO()
                .setLabel("开启定位重试")
                .setType("locate")
                .setQuery(Map.of("prompt", prompt));
    }

    private AiAssistantActionDTO shopListAction(String text, ShopType type) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (type != null) {
            query.put("typeId", type.getId());
            query.put("typeName", type.getName());
        } else {
            List<String> tokens = extractTokens(text);
            if (!tokens.isEmpty()) {
                query.put("name", tokens.get(0));
            }
        }
        return new AiAssistantActionDTO()
                .setLabel("打开店铺列表")
                .setType("route")
                .setRouteName("shops")
                .setQuery(query);
    }

    private ShopType matchType(String text, List<ShopType> shopTypes) {
        ShopType exact = shopTypes.stream()
                .filter(type -> StrUtil.isNotBlank(type.getName()) && text.contains(type.getName()))
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact;
        }
        return shopTypes.stream()
                .filter(type -> TYPE_ALIASES.getOrDefault(type.getName(), List.of()).stream().anyMatch(text::contains))
                .findFirst()
                .orElse(null);
    }

    private Integer extractMaxPrice(String text) {
        Matcher matcher = PRICE_PATTERN.matcher(text);
        if (matcher.find()) {
            return parseInt(matcher.group(1));
        }
        if (text.contains("人均") || text.contains("预算")) {
            Matcher number = NUMBER_PATTERN.matcher(text);
            if (number.find()) {
                return parseInt(number.group());
            }
        }
        return null;
    }

    private List<String> extractTokens(String text) {
        Set<String> tokens = new HashSet<>();
        TYPE_ALIASES.values().stream()
                .flatMap(List::stream)
                .filter(alias -> alias.length() >= 2 && text.contains(alias))
                .forEach(tokens::add);

        String normalized = text.replaceAll("[，。！？、,.!?；;：:]", " ");
        for (String part : normalized.split("\\s+")) {
            String token = part.trim();
            if (token.length() < 2 || STOP_WORDS.contains(token) || token.matches("\\d+")) {
                continue;
            }
            for (String stopWord : STOP_WORDS) {
                if (token.length() > stopWord.length() + 1) {
                    token = token.replace(stopWord, "");
                }
            }
            if (token.length() >= 2 && !STOP_WORDS.contains(token) && !token.matches("\\d+")) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty() && text.length() <= 8 && !STOP_WORDS.contains(text)) {
            tokens.add(text);
        }
        return new ArrayList<>(tokens).stream().limit(4).toList();
    }

    private String contextualText(AiChatRequest request) {
        StringBuilder builder = new StringBuilder();
        int historyLimit = positive(aiProperties.getHistoryLimit(), 6);
        List<AiChatMessageDTO> history = request.getHistory();
        if (history != null && historyLimit > 0) {
            history.stream()
                    .filter(item -> item != null && "user".equals(item.getRole()) && StrUtil.isNotBlank(item.getText()))
                    .skip(Math.max(0, history.size() - historyLimit))
                    .forEach(item -> builder.append(item.getText()).append(' '));
        }
        builder.append(request.getMessage());
        return builder.toString().trim();
    }

    private List<ShopType> safeShopTypes() {
        try {
            return shopTypeService.queryShopType();
        } catch (Exception error) {
            log.debug("Load shop types for AI skipped: {}", error.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> failureSamples(String jobId) {
        try {
            return shopGeoBatchService.getJobItems(jobId).stream()
                    .filter(item -> "failed".equals(item.getStatus()))
                    .limit(5)
                    .map(this::failureSample)
                    .toList();
        } catch (Exception error) {
            log.debug("Load failed job samples skipped: {}", error.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> failureSample(ShopGeoBatchJobItem item) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("shopId", item.getShopId());
        sample.put("shopName", item.getShopName());
        sample.put("message", item.getMessage());
        return sample;
    }

    private Map<String, Object> safeAmapStatus() {
        try {
            return amapGeoService.getStatus();
        } catch (Exception error) {
            log.debug("Load Amap status for AI skipped: {}", error.getMessage());
            return Map.of();
        }
    }

    private boolean isAmapCircuitOpen(Map<String, Object> amapStatus) {
        return Boolean.TRUE.equals(amapStatus.get("circuitOpen"));
    }

    private String defaultCity() {
        return geoProperties == null ? "杭州" : geoProperties.resolveCity(null);
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception error) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String polishWithModel(String userMessage, AiChatResponse base) {
        if (!Boolean.TRUE.equals(aiProperties.getEnabled())
                || StrUtil.isBlank(aiProperties.getApiKey())
                || StrUtil.isBlank(aiProperties.getModel())) {
            return base.getMessage();
        }
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", aiProperties.getModel());
            request.put("temperature", 0.2);
            request.put("messages", List.of(
                    Map.of("role", "system", "content", "你是黑马点评的业务助手。只能基于给定 JSON 总结，不要编造店铺、价格、距离或运营数据。回答保持简短、有行动建议。"),
                    Map.of("role", "user", "content", userMessage),
                    Map.of("role", "assistant", "content", JSONUtil.toJsonStr(base))
            ));
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(aiProperties.getApiKey());
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(request, headers);
            Map<?, ?> response = restTemplate.postForObject(aiProperties.getApiUrl(), entity, Map.class);
            if (response == null || !(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
                return base.getMessage();
            }
            Object first = choices.get(0);
            if (!(first instanceof Map<?, ?> choice) || !(choice.get("message") instanceof Map<?, ?> modelMessage)) {
                return base.getMessage();
            }
            String content = String.valueOf(modelMessage.get("content"));
            return StrUtil.isBlank(content) ? base.getMessage() : content;
        } catch (Exception error) {
            log.debug("AI model polish skipped: {}", error.getMessage());
            return base.getMessage();
        }
    }

    private boolean isRateLimited(AiChatRequest request) {
        int limit = rateLimitFor(request);
        if (limit <= 0) {
            return false;
        }
        try {
            String identity = clientIdentity();
            String key = AI_RATE_LIMIT_KEY + request.getScene() + ":" + identity + ":" + RATE_BUCKET_FORMATTER.format(LocalDateTime.now());
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(key, 70, TimeUnit.SECONDS);
            }
            return count != null && count > limit;
        } catch (Exception error) {
            log.debug("AI rate limit skipped: {}", error.getMessage());
            return false;
        }
    }

    private int rateLimitFor(AiChatRequest request) {
        UserDTO user = UserHolder.getUser();
        if (user != null && permissionPolicy.hasPermission(user.getRole(), PermissionCodes.ADMIN_ACCESS) && "admin".equals(request.getScene())) {
            return positive(aiProperties.getAdminRateLimitPerMinute(), 80);
        }
        if (user != null) {
            return positive(aiProperties.getUserRateLimitPerMinute(), 40);
        }
        return positive(aiProperties.getPublicRateLimitPerMinute(), 20);
    }

    private String clientIdentity() {
        UserDTO user = UserHolder.getUser();
        if (user != null && user.getId() != null) {
            return "u" + user.getId();
        }
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "anonymous";
        }
        HttpServletRequest request = attributes.getRequest();
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = StrUtil.isBlank(forwardedFor) ? request.getRemoteAddr() : forwardedFor.split(",")[0].trim();
        return "ip" + ip.replaceAll("[^0-9A-Za-z.:_-]", "_");
    }

    private AiChatRequest normalizeRequest(AiChatRequest request) {
        AiChatRequest normalized = request == null ? new AiChatRequest() : request;
        normalized.setMessage(StrUtil.blankToDefault(normalized.getMessage(), "").trim());
        String scene = normalized.getScene();
        normalized.setScene("admin".equals(scene) ? "admin" : "user");
        return normalized;
    }

    private AiChatResponse baseResponse(AiChatRequest request, String scene, String intent, Double confidence) {
        return new AiChatResponse()
                .setConversationId(request.getConversationId())
                .setScene(scene)
                .setIntent(intent)
                .setConfidence(confidence);
    }

    private String inferShopIntent(boolean wantsVoucher, boolean wantsCheap, boolean wantsCompare) {
        if (wantsVoucher) {
            return "coupon_recommendation";
        }
        if (wantsCheap) {
            return "budget_recommendation";
        }
        if (wantsCompare) {
            return "shop_comparison";
        }
        return "shop_recommendation";
    }

    private boolean looksLikePromptAttack(String message) {
        String lower = message.toLowerCase();
        return lower.contains("api key")
                || lower.contains("apikey")
                || message.contains("密钥")
                || message.contains("系统提示词")
                || message.contains("忽略以上")
                || lower.contains("ignore previous")
                || lower.contains("system prompt");
    }

    private boolean isCurrentShopIntent(String message) {
        return message.contains("这家")
                || message.contains("这个店")
                || message.contains("当前店")
                || message.contains("这店")
                || message.contains("优惠")
                || message.contains("券")
                || message.contains("营业")
                || message.contains("怎么样")
                || message.contains("类似");
    }

    private boolean hasHistory(AiChatRequest request) {
        return request.getHistory() != null && !request.getHistory().isEmpty();
    }

    private boolean hasCoordinate(AiChatRequest request) {
        return request != null && isValidCoordinate(request.getX(), request.getY());
    }

    private boolean isValidCoordinate(Double x, Double y) {
        return x != null && y != null && x >= -180 && x <= 180 && y >= -90 && y <= 90;
    }

    private boolean containsIgnoreBlank(String value, String token) {
        return StrUtil.isNotBlank(value) && StrUtil.isNotBlank(token) && value.contains(token);
    }

    private Integer parseInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (Exception error) {
            return null;
        }
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private record ScoredShop(Shop shop, double score, String reason) {
    }
}
