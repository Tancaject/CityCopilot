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

@Slf4j
@Service
public class ShopDiscoveryService {

    private static final double LOCAL_PRIORITY_DISTANCE_BAND_METERS = 250D;

    private final List<ShopDiscoveryProvider> providers;
    private final GeoProperties geoProperties;

    public ShopDiscoveryService(List<ShopDiscoveryProvider> providers, GeoProperties geoProperties) {
        this.providers = providers == null ? List.of() : providers;
        this.geoProperties = geoProperties;
    }

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
