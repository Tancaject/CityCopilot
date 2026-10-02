package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多数据源店铺发现编排服务。
 *
 * <p>依次收集本地与外部 Provider，隔离单源故障，再统一排序、去重和分页。</p>
 */
@Slf4j
@Service
public class ShopDiscoveryService {

    /** 同一距离带内优先本地店铺，避免细小测距误差压过可下单事实源。 */
    private static final double LOCAL_PRIORITY_DISTANCE_BAND_METERS = 250D;

    /** Spring 注入的全部店铺发现数据源。 */
    private final List<ShopDiscoveryProvider> providers;

    /** 分页大小和 Redis 候选上限配置。 */
    private final GeoProperties geoProperties;

    /**
     * @param providers 全部发现数据源
     * @param geoProperties GEO 分页配置
     */
    public ShopDiscoveryService(List<ShopDiscoveryProvider> providers, GeoProperties geoProperties) {
        this.providers = providers == null ? List.of() : providers;
        this.geoProperties = geoProperties;
    }

    /**
     * 执行一次跨数据源店铺发现。
     *
     * @param query 查询条件；空值按默认查询处理
     * @return 合并去重后的当前页及总数
     */
    public Result discover(ShopDiscoveryQuery query) {
        ShopDiscoveryQuery actualQuery = query == null ? new ShopDiscoveryQuery() : query;
        if (actualQuery.hasPoint() && !actualQuery.hasValidPoint()) {
            return Result.fail("SHOP_GEO_INVALID", "坐标参数不合法");
        }

        int pageSize = actualQuery.resolvePageSize(geoProperties);
        long from = actualQuery.resolveOffset(geoProperties);

        List<ShopDiscoveryItemDTO> merged = mergeCandidates(collectCandidates(actualQuery));

        if (from >= merged.size()) {
            return Result.ok(List.of(), (long) merged.size());
        }
        int fromIndex = (int) from;
        int to = (int) Math.min(from + pageSize, merged.size());
        return Result.ok(merged.subList(fromIndex, to), (long) merged.size());
    }

    /**
     * 尽力调用每个 Provider，单个外部源失败不会阻断其余结果。
     *
     * @param query 统一发现条件
     * @return 尚未排序和去重的候选
     */
    private List<ShopDiscoveryItemDTO> collectCandidates(ShopDiscoveryQuery query) {
        List<ShopDiscoveryItemDTO> candidates = new ArrayList<>();
        for (ShopDiscoveryProvider provider : providers) {
            try {
                List<ShopDiscoveryItemDTO> items = provider.discover(query);
                if (items != null) {
                    candidates.addAll(items);
                }
            } catch (Exception error) {
                log.warn("Shop discovery provider {} failed: {}", provider.getClass().getSimpleName(), error.getMessage());
            }
        }
        return candidates;
    }

    /**
     * 过滤无展示名称的候选，并按搜索排名、距离和来源合并重复项。
     *
     * @param candidates 原始候选
     * @return 稳定排序且去重后的列表
     */
    private List<ShopDiscoveryItemDTO> mergeCandidates(List<ShopDiscoveryItemDTO> candidates) {
        List<ShopDiscoveryItemDTO> sortedCandidates = candidates.stream()
                .filter(this::hasDisplayName)
                .sorted(discoveryComparator())
                .toList();
        List<ShopDiscoveryItemDTO> merged = new ArrayList<>();
        sortedCandidates.forEach(item -> addOrReplaceDuplicate(merged, item));
        return merged;
    }

    private boolean hasDisplayName(ShopDiscoveryItemDTO item) {
        return item != null && StrUtil.isNotBlank(item.getName());
    }

    /**
     * 构建稳定排序器：搜索引擎排名优先，其次距离带、本地来源、实际距离和名称。
     *
     * @return 候选比较器
     */
    private Comparator<ShopDiscoveryItemDTO> discoveryComparator() {
        return Comparator
                .comparingInt(this::searchRankBand)
                .thenComparingInt(this::searchRank)
                .thenComparingInt(this::distanceBand)
                .thenComparingInt(this::sourceWeight)
                .thenComparingDouble(this::distanceValue)
                .thenComparing(item -> StrUtil.blankToDefault(item.getName(), ""));
    }

    private int searchRankBand(ShopDiscoveryItemDTO item) {
        return item.getSearchRank() == null ? 1 : 0;
    }

    private int searchRank(ShopDiscoveryItemDTO item) {
        return item.getSearchRank() == null ? Integer.MAX_VALUE : item.getSearchRank();
    }

    private int distanceBand(ShopDiscoveryItemDTO item) {
        Double distance = item.getDistance();
        if (distance == null || distance < 0D) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(distance / LOCAL_PRIORITY_DISTANCE_BAND_METERS);
    }

    private int sourceWeight(ShopDiscoveryItemDTO item) {
        return "local".equals(item.getSource()) ? 0 : 1;
    }

    private double distanceValue(ShopDiscoveryItemDTO item) {
        Double distance = item.getDistance();
        return distance == null || distance < 0D ? Double.MAX_VALUE : distance;
    }

    /**
     * 将候选加入结果；发现同一 POI 时优先保留可下单的本地记录。
     *
     * @param target 已合并候选
     * @param item 新候选
     */
    private void addOrReplaceDuplicate(List<ShopDiscoveryItemDTO> target, ShopDiscoveryItemDTO item) {
        List<String> itemKeys = dedupeKeys(item);
        for (int index = 0; index < target.size(); index++) {
            ShopDiscoveryItemDTO existing = target.get(index);
            if (hasSharedKey(dedupeKeys(existing), itemKeys)) {
                if (isLocal(item) && !isLocal(existing)) {
                    target.set(index, item);
                }
                return;
            }
        }
        target.add(item);
    }

    private boolean hasSharedKey(List<String> left, List<String> right) {
        Set<String> keys = new HashSet<>(left);
        return right.stream().anyMatch(keys::contains);
    }

    private boolean isLocal(ShopDiscoveryItemDTO item) {
        return "local".equals(item.getSource());
    }

    /**
     * 为候选构造由强到弱的去重键：POI、名称地址、名称坐标，最后退化为来源主键。
     *
     * @param item 店铺候选
     * @return 一个或多个稳定去重键
     */
    private List<String> dedupeKeys(ShopDiscoveryItemDTO item) {
        List<String> keys = new ArrayList<>();
        if (StrUtil.isNotBlank(item.getPoiId())) {
            keys.add("poi:" + item.getPoiId());
        }
        String name = DiscoveryGeoUtils.normalize(item.getName());
        String address = DiscoveryGeoUtils.normalize(item.getAddress());
        if (StrUtil.isNotBlank(name) && StrUtil.isNotBlank(address)) {
            keys.add("name-address:" + name + "|" + address);
        }
        String x = DiscoveryGeoUtils.coordinateBucket(item.getX());
        String y = DiscoveryGeoUtils.coordinateBucket(item.getY());
        if (StrUtil.isNotBlank(name) && StrUtil.isNotBlank(x) && StrUtil.isNotBlank(y)) {
            keys.add("name-point:" + name + "|" + x + "|" + y);
        }
        if (keys.isEmpty()) {
            keys.add("source:" + item.getSource() + ":" + item.getSourceId());
        }
        return keys;
    }
}
